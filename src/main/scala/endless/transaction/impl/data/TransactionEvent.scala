package endless.transaction.impl.data

import cats.data.NonEmptyList
import endless.transaction.Branch

private[transaction] sealed trait TransactionEvent[+TID, +BID, +Q, +R]

private[transaction] object TransactionEvent {
  final case class Created[TID, BID, Q](id: TID, query: Q, branches: NonEmptyList[BID])
      extends TransactionEvent[TID, BID, Q, Nothing]

  final case class BranchVoted[BID, R](branch: BID, vote: Branch.Vote[R])
      extends TransactionEvent[Nothing, BID, Nothing, R]

  final case class ClientAborted[R](reason: Option[R])
      extends TransactionEvent[Nothing, Nothing, Nothing, R]

  final case class BranchCommitted[BID](branch: BID)
      extends TransactionEvent[Nothing, BID, Nothing, Nothing]

  final case class BranchAborted[BID](branch: BID)
      extends TransactionEvent[Nothing, BID, Nothing, Nothing]

  final case class BranchFailed[BID](branch: BID, error: String)
      extends TransactionEvent[Nothing, BID, Nothing, Nothing]

  final object Timeout extends TransactionEvent[Nothing, Nothing, Nothing, Nothing]
}
