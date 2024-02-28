package endless.transaction.impl.logic

import cats.{Applicative, Show}
import cats.data.{EitherT, NonEmptyList}
import cats.syntax.applicative.*
import cats.syntax.either.*
import cats.syntax.functor.*
import endless.\/
import endless.core.entity.Entity
import endless.transaction.Transaction.{
  AbortError,
  Status,
  TooLateToAbort,
  TransactionFailed,
  Unknown
}
import endless.transaction.impl.algebra.{TransactionAlg, TransactionCreator}
import endless.transaction.impl.data.{TransactionEvent, TransactionState}
import endless.transaction.{Branch, Transaction}
import org.typelevel.log4cats.Logger

private[transaction] final class TransactionEntityBehavior[F[_]: Logger, TID, BID: Show, Q, R](
    entity: Entity[F, TransactionState[TID, BID, Q, R], TransactionEvent[TID, BID, Q, R]]
) extends TransactionAlg[F, TID, BID, Q, R] {
  import entity.*
  private val unit = Applicative[F].unit

  def create(
      id: TID,
      query: Q,
      branches: NonEmptyList[BID]
  ): F[TransactionCreator.AlreadyExists.type \/ Unit] = ifUnknownF(
    write(TransactionEvent.Created(id, query, branches))
  )(_ => TransactionCreator.AlreadyExists)

  def query: F[Transaction.Unknown.type \/ Q] = ifKnown(_.query)(Unknown)

  def branches: F[Transaction.Unknown.type \/ Set[BID]] = ifKnown(_.branches)(Unknown)

  def status: F[Transaction.Unknown.type \/ Transaction.Status[R]] = ifKnown(_.status)(Unknown)

  def abort(reason: Option[R]): F[AbortError \/ Unit] = ifKnownFE(_.status match {
    case Status.Preparing =>
      write(TransactionEvent.ClientAborted(reason)).map(_ => ().asRight[AbortError])
    case Status.Committing                      => (TooLateToAbort: AbortError).asLeft[Unit].pure
    case Status.Committed                       => (TooLateToAbort: AbortError).asLeft[Unit].pure
    case Status.Failed(_)                       => (TransactionFailed: AbortError).asLeft[Unit].pure
    case Status.Aborting(_) | Status.Aborted(_) => ().asRight[AbortError].pure
  })(Unknown: AbortError)

  def branchVoted(branch: BID, vote: Branch.Vote[R]): F[Unit] =
    EitherT(
      ifKnownF(state =>
        state
          .branchVoted(branch, vote)
          .fold(Logger[F].warn(_), _ => write(TransactionEvent.BranchVoted(branch, vote)))
      )("Transaction not found")
    ).foldF(Logger[F].warn(_), _ => unit)

  def branchCommitted(branch: BID): F[Unit] = EitherT(
    ifKnownF(state =>
      state
        .branchCommitted(branch)
        .fold(Logger[F].warn(_), _ => write(TransactionEvent.BranchCommitted(branch)))
    )("Transaction not found")
  ).foldF(Logger[F].warn(_), _ => unit)

  def branchAborted(branch: BID): F[Unit] = EitherT(
    ifKnownF(state =>
      state
        .branchAborted(branch)
        .fold(Logger[F].warn(_), _ => write(TransactionEvent.BranchAborted(branch)))
    )("Transaction not found")
  ).foldF(Logger[F].warn(_), _ => unit)

  def branchFailed(branch: BID, error: String): F[Unit] = EitherT(
    ifKnownF(state =>
      state
        .branchFailed(branch, error)
        .fold(Logger[F].warn(_), _ => write(TransactionEvent.BranchFailed(branch, error)))
    )("Transaction not found")
  ).foldF(Logger[F].warn(_), _ => unit)

  def timeout(): F[Unit] = EitherT(
    ifKnownF(state =>
      state.timeout().fold(Logger[F].warn(_), _ => write(TransactionEvent.Timeout))
    )("Transaction not found")
  ).foldF(Logger[F].warn(_), _ => unit)

}
