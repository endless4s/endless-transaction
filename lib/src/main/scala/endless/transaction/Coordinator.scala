package endless.transaction

import cats.effect.kernel.Resource

/** A coordinator allows for creating and recovering transactions of a certain type. Internally, it
  * implements the two-phase commit protocol to guarantee a final transaction state of either
  * committed or aborted.
  *
  * @tparam F
  *   the effect type
  * @tparam TID
  *   the transaction identifier type
  * @tparam BID
  *   the branch identifier type
  * @tparam Q
  *   the query type
  * @tparam R
  *   the abort reason type
  */
trait Coordinator[F[_], TID, BID, Q, R] {

  /** Creates a new transaction with the given id, query and branches, and returns a handle to it
    * wrapped in a `Resource`. Resource release leads to transaction abort if it is still pending
    * (or a no-op if the transaction is already completed).
    *
    * @note
    *   the transaction identifier must be unique and an exception will be raised in the target
    *   effect if a transaction with the same id already exists.
    * @param id
    *   the transaction id
    * @param query
    *   the query to be executed (transaction payload)
    * @param branch
    *   the first branch
    * @param otherBranches
    *   the remaining branches
    * @return
    *   handle to the transaction
    */
  def create(
      id: TID,
      query: Q,
      branch: BID,
      otherBranches: BID*
  ): Resource[F, Transaction[F, BID, Q, R]]

  /** Recovers a transaction with the given id. Can be used by recovering clients who lost the
    * handle to the transaction and that require further interaction with it, or to retrieve
    * information about past transactions.
    *
    * Resource release leads to transaction abort if it is still pending (or a no-op if the
    * transaction is already completed).
    *
    * @note
    *   by default, pending transactions are recovered automatically upon coordinator startup
    *   (thanks to remember-entities). There is therefore no need to access them via this method
    *   unless further interaction is required. However, if an alternative to remember-entities is
    *   needed, this method chained with e.g. retrieving the status can be used to trigger recovery
    *   and associated side-effects for pending transactions.
    * @param id
    *   the transaction id
    * @return
    *   handle to the transaction
    */
  def get(id: TID): Resource[F, Transaction[F, BID, Q, R]]
}

object Coordinator {
  class TransactionAlreadyExists extends Exception("Transaction with the given ID already exists")
}
