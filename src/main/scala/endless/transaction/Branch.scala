package endless.transaction

import endless.transaction.Branch.Vote

/** A branch defines behavior for the various phases of the 2PC protocol for a certain transaction
  * type. The branch is responsible for preparing, committing and aborting the transaction. The
  * coordinator instantiates a branch for each transaction and branch ID.
  * @tparam F
  *   the effect type
  * @tparam TID
  *   the transaction identifier type
  * @tparam Q
  *   the query type
  * @tparam R
  *   the abort reason type
  */
trait Branch[F[_], TID, Q, R] {

  /** Prepare the branch for a transaction. This is the first step in the 2PC protocol.
    *
    * @note
    *   this operation supports an optional timeout (this is tracked by the coordinator), in which
    *   case the transaction will be aborted. However, there is no intrinsic limitation in the
    *   duration this operation may take. It could span days, as long as it returns a vote at some
    *   point and the coordinator is still alive.
    * @note
    *   this operation is expected to be idempotent, as it may be retried by the coordinator.
    * @note
    *   an exception raised here transitions the transaction to failed state (once all branches *
    *   return).
    * @param id
    *   transaction id
    * @param query
    *   payload relevant for transaction execution
    * @return
    *   vote on whether the transaction should be committed or aborted
    */
  def prepare(id: TID, query: Q): F[Vote[R]]

  /** Commit the transaction branch. This is the second step in the 2PC protocol.
    *
    * @note
    *   this operation should in principle not fail, all branches are expected to commit at this
    *   point to ensure consistency. It's up to the branch to organize retries if necessary.
    * @note
    *   this operation is expected to be idempotent, as it may be retried by the coordinator.
    * @note
    *   an exception raised here transitions the transaction to a failed state (once all branches
    *   return).
    * @note
    *   as for prepare, there is no intrinsic limitation in the duration this operation may take. A
    *   confirmation is expected by the coordinator at some point to transition the transaction to
    *   committed, however.
    * @param id
    *   transaction id
    */
  def commit(id: TID): F[Unit]

  /** Abort the transaction branch. This is the second step in the 2PC protocol, in case of
    * preparation timeout or any branch voting to abort.
    *
    * @note
    *   all branches are expected to abort at this point, failure could lead to local inconsistency.
    *   It's up to the branch to organize retries if necessary.
    * @note
    *   this operation is expected to be idempotent, as it may be retried by the coordinator.
    * @note
    *   an exception raised here transitions the transaction to failed state (once all branches
    *   return).
    * @note
    *   as for prepare, there is no intrinsic limitation in the duration this operation may take. A
    *   confirmation is expected by the coordinator at some point to transition the transaction to
    *   aborted, however.
    * @param id
    *   transaction id
    */
  def abort(id: TID): F[Unit]
}

object Branch {
  def apply[F[_], TID, BID, Q, R](
      prepareF: (TID, Q) => F[Vote[R]],
      commitF: TID => F[Unit],
      abortF: TID => F[Unit]
  ): Branch[F, TID, Q, R] =
    new Branch[F, TID, Q, R] {
      def prepare(id: TID, query: Q): F[Vote[R]] = prepareF(id, query)
      def commit(id: TID): F[Unit] = commitF(id)
      def abort(id: TID): F[Unit] = abortF(id)
    }

  /** The vote of a branch in the 2PC protocol.
    * @tparam R
    *   the abort reason type
    */
  sealed trait Vote[+R]
  object Vote {

    /** The branch votes to commit the transaction.
      */
    case object Commit extends Vote[Nothing]

    /** The branch votes to abort the transaction with the specified reason. */
    final case class Abort[R](reason: R) extends Vote[R]
  }
}
