package endless.transaction.impl.logic

import cats.Show
import cats.effect.kernel.{Async, Temporal}
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import cats.syntax.show.*
import cats.effect.kernel.implicits.parallelForGenSpawn
import endless.core.entity.SideEffect.Trigger
import endless.core.entity.SideEffect.Trigger.{AfterPersistence, AfterRead, AfterRecovery}
import endless.core.entity.{Effector, SideEffect}
import endless.transaction.Branch
import endless.transaction.Transaction.Status
import endless.transaction.impl.algebra.TransactionAlg
import endless.transaction.impl.data.TransactionState
import endless.transaction.impl.data.TransactionState.*
import endless.transaction.impl.helpers.RetryHelpers.*
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.{Duration, FiniteDuration}

private[transaction] final class TransactionSideEffect[
    F[_]: Temporal: Logger,
    TID,
    BID: Show,
    Q,
    R
](
    timeoutSideEffect: TimeoutSideEffect[F],
    branchForID: BID => Branch[F, TID, Q, R]
)(implicit retryParameters: RetryParameters)
    extends SideEffect[
      F,
      TransactionState[TID, BID, Q, R],
      ({ type T[G[_]] = TransactionAlg[G, TID, BID, Q, R] })#T
    ] {

  def apply(
      trigger: Trigger,
      effector: Effector[
        F,
        TransactionState[TID, BID, Q, R],
        ({ type T[G[_]] = TransactionAlg[G, TID, BID, Q, R] })#T
      ]
  ): F[Unit] = {
    import effector.*

    lazy val branchEffect: F[Unit] = ifKnown {
      case preparing: Preparing[TID, BID, Q, R] if (trigger match {
            case AfterPersistence =>
              preparing.noVotesYet // having at least a vote means we already sent prepares
            case AfterRecovery => true
            case AfterRead     => false
          }) =>
        preparing.branches
          .filterNot(preparing.hasBranchAlreadyVoted)
          .map(zipWithBranch)
          .toList
          .parTraverse((prepareBranch(preparing) _).tupled)
          .void

      case committing: Committing[TID, BID, Q, R] if (trigger match {
            case AfterPersistence =>
              committing.noCommitsYet // having at least a commit means we already sent commits
            case AfterRecovery => true
            case AfterRead     => false
          }) =>
        committing.branches
          .filterNot(committing.hasBranchAlreadyCommitted)
          .map(zipWithBranch)
          .toList
          .parTraverse((commitBranch(committing) _).tupled)
          .void

      case aborting: Aborting[TID, BID, Q, R] if (trigger match {
            case AfterPersistence =>
              aborting.noAbortsYet // have at least an abort means we already sent aborts
            case AfterRecovery => true
            case AfterRead     => false
          }) =>
        aborting.branches
          .filterNot(aborting.hasBranchAlreadyAborted)
          .map(zipWithBranch)
          .toList
          .parTraverse((abortBranch(aborting) _).tupled)
          .void

      case _ => ().pure
    }

    def prepareBranch(
        state: Preparing[TID, BID, Q, R]
    )(branchID: BID, branch: Branch[F, TID, Q, R]) = branch
      .prepare(state.id, state.query)
      .flatMap(vote =>
        self
          .branchVoted(branchID, vote)
          .retryWithBackoff(warnAboutRetry("vote", branchID))
      )
      .handleErrorWith(throwable =>
        self
          .branchFailed(branchID, throwable.getMessage)
          .retryWithBackoff(warnAboutRetry("failure", branchID))
          .handleErrorWith(logDefinitiveFailure(branchID))
      )

    def commitBranch(state: Committing[TID, BID, Q, R])(
        branchID: BID,
        branch: Branch[F, TID, Q, R]
    ) = branch
      .commit(state.id)
      .flatMap(_ =>
        self
          .branchCommitted(branchID)
          .retryWithBackoff(warnAboutRetry("commit", branchID))
      )
      .handleErrorWith(throwable =>
        self
          .branchFailed(branchID, throwable.getMessage)
          .retryWithBackoff(warnAboutRetry("failure", branchID))
          .handleErrorWith(logDefinitiveFailure(branchID))
      )

    def abortBranch(
        state: Aborting[TID, BID, Q, R]
    )(branchID: BID, branch: Branch[F, TID, Q, R]) = {
      branch
        .abort(state.id)
        .flatMap(_ =>
          self
            .branchAborted(branchID)
            .retryWithBackoff(warnAboutRetry("abort", branchID))
        )
        .handleErrorWith(throwable =>
          self
            .branchFailed(branchID, throwable.getMessage)
            .retryWithBackoff(warnAboutRetry("failure", branchID))
            .handleErrorWith(logDefinitiveFailure(branchID))
        )
    }

    lazy val timeoutEffect =
      ifKnown(state => timeoutSideEffect.scheduleTimeoutAccordingTo(state.status))

    lazy val passivationEffect = ifKnown(state =>
      state.status match {
        case _: Status.Pending[R] => disablePassivation
        case _: Status.Final[R]   => enablePassivation()
      }
    )

    trigger match {
      case Trigger.AfterPersistence | Trigger.AfterRecovery =>
        branchEffect >> timeoutEffect >> passivationEffect
      case Trigger.AfterRead => passivationEffect
    }
  }

  private def logDefinitiveFailure(branchID: BID)(throwable: Throwable) = {
    Logger[F].error(throwable)(
      show"Definitive failure to notify transaction about failure for branch $branchID"
    )
  }

  private def warnAboutRetry(operation: String, branchID: BID): OnError[F] = (
      error: Throwable,
      delay: Duration,
      attemptNumber: Int,
      maxRetries: Int
  ) =>
    Logger[F].warn(error)(
      show"Failed to notify transaction about $operation for branch $branchID, retrying in $delay (attempt #$attemptNumber out of $maxRetries)"
    )

  private def zipWithBranch(branchID: BID) = branchID -> branchForID(branchID)
}

private[transaction] object TransactionSideEffect {
  def apply[F[_]: Async: Logger, TID, BID: Show, Q, R](
      timeout: Option[FiniteDuration],
      branchForID: BID => Branch[F, TID, Q, R],
      alg: TransactionAlg[F, TID, BID, Q, R]
  )(implicit retryParameters: RetryParameters): F[SideEffect[
    F,
    TransactionState[TID, BID, Q, R],
    ({ type T[G[_]] = TransactionAlg[G, TID, BID, Q, R] })#T
  ]] =
    TimeoutSideEffect[F](timeout, alg.timeout()).map(timeoutEffect =>
      new TransactionSideEffect[F, TID, BID, Q, R](timeoutEffect, branchForID)
    )

}
