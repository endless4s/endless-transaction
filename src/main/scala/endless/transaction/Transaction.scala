package endless.transaction

import cats.data.NonEmptyList
import cats.effect.kernel.Temporal
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import endless.\/
import endless.transaction.Transaction.*

import scala.concurrent.duration.*

/** This defines a handle on a distributed transaction, which can be used to inspect its status,
  * retrieve its query and participating branches and trigger a client abort.
  * @tparam F
  *   the effect type
  * @tparam BID
  *   the branch identifier type
  * @tparam Q
  *   the query type
  * @tparam R
  *   the abort reason type
  */
trait Transaction[F[_], BID, Q, R] {
  def query: F[Unknown.type \/ Q]
  def branches: F[Unknown.type \/ Set[BID]]
  def status: F[Unknown.type \/ Status[R]]
  def abort(reason: Option[R] = None): F[AbortError \/ Unit]
}

object Transaction {

  /** The reason for a transaction abort.
    * @tparam R
    *   the abort reason type
    */
  sealed trait AbortReason[+R]
  object AbortReason {

    /** The transaction has been aborted due to a timeout during the prepare phase.
      */
    object Timeout extends AbortReason[Nothing]

    /** The transaction has been aborted due to a vote against commit by at least one of the
      * participating branches.
      * @param reasons
      *   the reasons for the abort
      */
    final case class Branches[R](reasons: NonEmptyList[R]) extends AbortReason[R]

    /** The transaction has been aborted due to a request by the client.
      * @param reason
      *   the reason for the abort
      */
    final case class Client[R](reason: Option[R]) extends AbortReason[R]
  }

  case object Unknown extends AbortError

  sealed trait AbortError
  case object TooLateToAbort extends AbortError
  case object TransactionFailed extends AbortError

  /** The status of a transaction.
    * @tparam R
    *   the abort reason type
    */
  sealed trait Status[+R]
  object Status {

    /** Pending transaction status
      * @tparam R
      *   the abort reason type
      */
    sealed trait Pending[+R] extends Status[R]

    /** Final transaction status
      * @tparam R
      *   the abort reason type
      */
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

    /** Polls for the status of the transaction until it reaches a final state.
      * @param frequency
      *   the polling frequency
      * @return
      *   the final status of the transaction (either committed or aborted)
      */
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
