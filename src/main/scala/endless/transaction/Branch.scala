package endless.transaction

import endless.transaction.Branch.Vote

trait Branch[F[_], TID, BID, Q, R] {

  /** Prepare the branch for a transaction. This is the first step in the 2PC protocol.
    *
    * @note
    *   this operation can timeout (this is tracked by the coordinator), in which case the
    *   transaction will be aborted.
    * @param id
    *   transaction id
    * @param query
    *   payload relevant for transaction execution
    * @note
    *   an exception raised here transitions the transaction to failed state (once all branches
    *   return).
    * @return
    *   vote on whether the transaction should be committed or aborted
    */
  def prepare(id: TID, query: Q): F[Vote[R]]

  /** Commit the transaction branch. This is the second step in the 2PC protocol.
    *
    * @note
    *   this operation cannot fail, all branches are expected to commit at this point to ensure
    *   consistency. It's up to the branch to organize retries if necessary
    * @note
    *   an exception raised here transitions the transaction to failed state (once all branches
    *   return).
    * @param id
    *   transaction id
    */
  def commit(id: TID): F[Unit]

  /** Abort the transaction branch. This is the second step in the 2PC protocol, in case of
    * preparation timeout or any branch voting to abort.
    *
    * @note
    *   all branches are expected to abort at this point, failure to do so could lead to local
    *   inconsistency. It's up to the branch to organize retries if necessary.
    * @note
    *   an exception raised here transitions the transaction to failed state (once all branches
    *   return).
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
  ): Branch[F, TID, BID, Q, R] =
    new Branch[F, TID, BID, Q, R] {
      def prepare(id: TID, query: Q): F[Vote[R]] = prepareF(id, query)
      def commit(id: TID): F[Unit] = commitF(id)
      def abort(id: TID): F[Unit] = abortF(id)
    }

  sealed trait Vote[+R]
  object Vote {
    case object Commit extends Vote[Nothing]
    final case class Abort[R](reason: R) extends Vote[R]
  }
}
