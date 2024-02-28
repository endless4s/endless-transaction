package endless.transaction.impl.logic

import cats.syntax.either.*
import endless.\/
import endless.core.event.EventApplier
import endless.transaction.Branch.Vote
import endless.transaction.impl.data.TransactionEvent.*
import endless.transaction.impl.data.TransactionState.*
import endless.transaction.impl.data.{TransactionEvent, TransactionState}

private[transaction] final class TransactionEventApplier[TID, BID, Q, R]
    extends EventApplier[TransactionState[TID, BID, Q, R], TransactionEvent[TID, BID, Q, R]] {

  def apply(
      maybeState: Option[TransactionState[TID, BID, Q, R]],
      event: TransactionEvent[TID, BID, Q, R]
  ): String \/ Option[TransactionState[TID, BID, Q, R]] = (event match {
    case Created(transactionID, query, branches) =>
      maybeState
        .toLeft(
          Preparing(transactionID, branches.map(_ -> Option.empty[Vote[R]]).toList.toMap, query)
        )
        .leftMap(_ => "Transaction already exists")

    case BranchVoted(branch, vote) => stateOrError(maybeState).flatMap(_.branchVoted(branch, vote))

    case ClientAborted(reason) => stateOrError(maybeState).flatMap(_.clientAborted(reason))

    case BranchCommitted(branch) => stateOrError(maybeState).flatMap(_.branchCommitted(branch))

    case BranchAborted(branch) => stateOrError(maybeState).flatMap(_.branchAborted(branch))

    case BranchFailed(branch, error) =>
      stateOrError(maybeState).flatMap(_.branchFailed(branch, error))

    case Timeout => stateOrError(maybeState).flatMap(_.timeout())

  }).map(Option(_))

  private def stateOrError(maybeState: Option[TransactionState[TID, BID, Q, R]]) =
    maybeState.toRight("Missing transaction state")
}
