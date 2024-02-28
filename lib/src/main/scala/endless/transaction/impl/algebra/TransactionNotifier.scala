package endless.transaction.impl.algebra

import endless.transaction.Branch

private[transaction] trait TransactionNotifier[F[_], BID, Q, R] {
  def branchVoted(branch: BID, vote: Branch.Vote[R]): F[Unit]
  def branchCommitted(branch: BID): F[Unit]
  def branchAborted(branch: BID): F[Unit]
  def branchFailed(branch: BID, error: String): F[Unit]
  def timeout(): F[Unit]
}
