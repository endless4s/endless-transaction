package endless.transaction.impl.logic

import cats.effect.kernel.{Async, Temporal}
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import cats.effect.kernel.implicits.parallelForGenSpawn
import endless.core.entity.SideEffect.Trigger
import endless.core.entity.SideEffect.Trigger.{AfterPersistence, AfterRead, AfterRecovery}
import endless.core.entity.{Effector, SideEffect}
import endless.transaction.Branch
import endless.transaction.Transaction.Status
import endless.transaction.impl.algebra.TransactionAlg
import endless.transaction.impl.data.TransactionState
import endless.transaction.impl.data.TransactionState.*

import scala.concurrent.duration.FiniteDuration

private[transaction] final class TransactionSideEffect[F[_]: Temporal, TID, BID, Q, R](
    timeoutSideEffect: TimeoutSideEffect[F],
    branchForID: BID => Branch[F, TID, BID, Q, R]
) extends SideEffect[
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
            case AfterPersistence => preparing.noVotesYet // only send prepares once, or on recovery
            case AfterRecovery    => true
            case AfterRead        => false
          }) =>
        preparing.branches
          .filterNot(preparing.hasBranchAlreadyVoted)
          .map(zipWithBranch)
          .toList
          .parTraverse { case (branchID, branch) =>
            branch
              .prepare(preparing.id, preparing.query)
              .flatMap(vote => self.branchVoted(branchID, vote))
              .handleErrorWith(throwable => self.branchFailed(branchID, throwable.getMessage))
          }
          .void

      case committing: Committing[TID, BID, Q, R] if (trigger match {
            case AfterPersistence =>
              committing.noCommitsYet // only send commits once, or on recovery
            case AfterRecovery => true
            case AfterRead     => false
          }) =>
        committing.branches
          .filterNot(committing.hasBranchAlreadyCommitted)
          .map(zipWithBranch)
          .toList
          .parTraverse { case (branchID, branch) =>
            branch
              .commit(committing.id)
              .flatMap(_ => self.branchCommitted(branchID))
              .handleErrorWith(throwable => self.branchFailed(branchID, throwable.getMessage))
          }
          .void

      case aborting: Aborting[TID, BID, Q, R] if (trigger match {
            case AfterPersistence =>
              aborting.noAbortsYet // only send aborts once, or on recovery
            case AfterRecovery => true
            case AfterRead     => false
          }) =>
        aborting.branches
          .filterNot(aborting.hasBranchAlreadyAborted)
          .map(zipWithBranch)
          .toList
          .parTraverse { case (branchID, branch) =>
            branch
              .abort(aborting.id)
              .flatMap(_ => self.branchAborted(branchID))
              .handleErrorWith(throwable => self.branchFailed(branchID, throwable.getMessage))
          }
          .void

      case _ => ().pure
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

  private def zipWithBranch(branchID: BID) = branchID -> branchForID(branchID)
}

private[transaction] object TransactionSideEffect {
  def apply[F[_]: Async, TID, BID, Q, R](
      timeout: Option[FiniteDuration],
      branchForID: BID => Branch[F, TID, BID, Q, R],
      alg: TransactionAlg[F, TID, BID, Q, R]
  ): F[SideEffect[
    F,
    TransactionState[TID, BID, Q, R],
    ({ type T[G[_]] = TransactionAlg[G, TID, BID, Q, R] })#T
  ]] =
    TimeoutSideEffect[F](timeout, alg.timeout()).map(timeoutEffect =>
      new TransactionSideEffect[F, TID, BID, Q, R](timeoutEffect, branchForID)
    )

}
