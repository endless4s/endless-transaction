package endless.transaction

import cats.data.NonEmptyList
import cats.effect.kernel.Temporal
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import endless.\/
import endless.transaction.Transaction.*

import scala.concurrent.duration.*

trait Transaction[F[_], BID, Q, R] {
  def query: F[Unknown.type \/ Q]
  def branches: F[Unknown.type \/ Set[BID]]
  def status: F[Unknown.type \/ Status[R]]
  def abort(reason: Option[R] = None): F[AbortError \/ Unit]
}

object Transaction {
  sealed trait AbortReason[+R]
  object AbortReason {
    object Timeout extends AbortReason[Nothing]
    final case class Branches[R](reasons: NonEmptyList[R]) extends AbortReason[R]
    final case class Client[R](reason: Option[R]) extends AbortReason[R]
  }

  case object Unknown extends AbortError

  sealed trait AbortError
  case object TooLateToAbort extends AbortError
  case object TransactionFailed extends AbortError

  sealed trait Status[+R]
  object Status {
    sealed trait Pending[+R] extends Status[R]
    sealed trait Final[+R] extends Status[R]

    /** Prepares have been issued by the coordinator and it is now waiting for votes from the
      * participating branches.
      */
    case object Preparing extends Pending[Nothing]

    /** All participating branches have voted in favor of transaction commit. Branch commits have
      * been issued by the coordinator, which is now waiting for branch commit confirmations.
      */
    case object Committing extends Pending[Nothing]

    /** Transaction has successfully completed, all participating branches have confirmed the
      * commit.
      */
    case object Committed extends Final[Nothing]

    /** At least one participating branch voted against transaction commit, or an abort has been
      * requested by the client in the meantime. Branch aborts have been issued by the coordinator,
      * which is now waiting for confirmation from the participating branches.
      */
    final case class Aborting[R](reason: AbortReason[R]) extends Pending[R]

    /** Transaction has been aborted and all participating branches have confirmed the abort.
      */
    final case class Aborted[R](reason: AbortReason[R]) extends Final[R]

    /** Transaction has failed due to an exception raised by one of the participating branches.
      */
    final case class Failed(errors: NonEmptyList[String]) extends Final[Nothing]
  }

  implicit class TransactionOps[F[_]: Temporal, BID, Q, R](
      transaction: Transaction[F, BID, Q, R]
  ) {
    def pollForFinalStatus(frequency: FiniteDuration = 200.milliseconds): F[Status.Final[R]] =
      for {
        status <- transaction.status.map(_.toOption)
        result <- status match {
          case None | Some(_: Status.Pending[R]) =>
            Temporal[F].sleep(frequency) >> transaction.pollForFinalStatus(frequency)
          case Some(finalStatus: Status.Final[R]) => finalStatus.pure
        }
      } yield result
  }
}
