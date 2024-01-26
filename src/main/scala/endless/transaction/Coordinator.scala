package endless.transaction

import cats.effect.kernel.Resource

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
    * @param branch1
    *   the first branch
    * @param branch2
    *   the second branch
    * @param others
    *   the remaining branches
    * @return
    *   handle to the transaction
    */
  def create(
      id: TID,
      query: Q,
      branch1: BID,
      branch2: BID,
      others: BID*
  ): Resource[F, Transaction[F, BID, Q, R]]

  /** Recovers a transaction with the given id. Can be used by recovering clients who lost the
    * handle to the transaction, or to retrieve information about past transactions.
    *
    * Resource release leads to transaction abort if it is still pending (or a no-op if the
    * transaction is already completed).
    *
    * @param id
    *   the transaction id
    * @return
    *   handle to the transaction
    */
  def get(id: TID): Resource[F, Transaction[F, BID, Q, R]]
}

object Coordinator {
  object TransactionAlreadyExists extends Exception("Transaction with the given ID already exists")
}
