package endless.transaction.impl.adapter

import cats.data.NonEmptyList
import com.google.protobuf.ByteString
import endless.core.protocol.{Decoder, Encoder}
import endless.transaction.impl.data.TransactionEvent
import endless.transaction.{BinaryCodec, proto}

private[transaction] class TransactionEventAdapter[TID, BID, Q, R](implicit
    tidCodec: BinaryCodec[TID],
    bidCodec: BinaryCodec[BID],
    qCodec: BinaryCodec[Q],
    rCodec: BinaryCodec[R]
) {
  def toJournal(event: TransactionEvent[TID, BID, Q, R]): proto.events.TransactionEvent =
    event match {
      case TransactionEvent.Created(id, query, branches) =>
        proto.events.Created(
          encodeTransactionID(id),
          encodeToByteString(query),
          branches.map(encodeBranchID).toList
        )

      case TransactionEvent.BranchVoted(branch, vote) =>
        proto.events.BranchVoted(
          encodeBranchID(branch),
          vote match {
            case endless.transaction.Branch.Vote.Commit =>
              proto.model.Vote(
                endless.transaction.proto.model.Vote.Vote
                  .Commit(endless.transaction.proto.model.Commit())
              )
            case endless.transaction.Branch.Vote.Abort(reason) =>
              proto.model.Vote(
                endless.transaction.proto.model.Vote.Vote
                  .Abort(endless.transaction.proto.model.Abort(encodeToByteString(reason)))
              )
          }
        )

      case TransactionEvent.ClientAborted(reason) =>
        proto.events.ClientAborted(reason.map(encodeToByteString[R]).getOrElse(ByteString.EMPTY))

      case TransactionEvent.BranchCommitted(branch) =>
        proto.events.BranchCommitted(encodeBranchID(branch))

      case TransactionEvent.BranchAborted(branch) =>
        proto.events.BranchAborted(encodeBranchID(branch))

      case TransactionEvent.BranchFailed(branch, error) =>
        proto.events.BranchFailed(encodeBranchID(branch), error)

      case TransactionEvent.Timeout =>
        proto.events.Timeout()
    }

  def fromJournal(event: proto.events.TransactionEvent): TransactionEvent[TID, BID, Q, R] =
    event match {
      case proto.events.Created(id, query, branches, _) =>
        TransactionEvent.Created(
          decodeTransactionID(id),
          decodeFromByteString(query),
          NonEmptyList.fromListUnsafe(branches.map(decodeBranchID).toList)
        )

      case proto.events.BranchVoted(branch, vote, _) =>
        TransactionEvent.BranchVoted(
          decodeBranchID(branch),
          vote.vote match {
            case proto.model.Vote.Vote.Empty => throw new IllegalArgumentException("Empty vote")
            case proto.model.Vote.Vote.Commit(_) =>
              endless.transaction.Branch.Vote.Commit
            case proto.model.Vote.Vote.Abort(abort) =>
              endless.transaction.Branch.Vote.Abort(decodeFromByteString(abort.reason))
          }
        )

      case proto.events.ClientAborted(reason, _) =>
        TransactionEvent.ClientAborted(Option.unless(reason.isEmpty)(decodeFromByteString(reason)))

      case proto.events.BranchCommitted(branch, _) =>
        TransactionEvent.BranchCommitted(decodeBranchID(branch))

      case proto.events.BranchAborted(branch, _) =>
        TransactionEvent.BranchAborted(decodeBranchID(branch))

      case proto.events.BranchFailed(branch, error, _) =>
        TransactionEvent.BranchFailed(decodeBranchID(branch), error)

      case proto.events.Timeout(_) =>
        TransactionEvent.Timeout
    }

  private def encodeBranchID(branch: BID) =
    proto.model.BranchID(encodeToByteString(branch)(using bidCodec))

  private def decodeBranchID(branch: proto.model.BranchID) =
    decodeFromByteString(branch.value)(using bidCodec)

  private def encodeTransactionID(id: TID) =
    proto.model.TransactionID(encodeToByteString(id)(using tidCodec))

  private def decodeTransactionID(id: proto.model.TransactionID) =
    decodeFromByteString(id.value)(using tidCodec)

  private def encodeToByteString[A](a: A)(implicit encoder: Encoder[A]): ByteString =
    ByteString.copyFrom(encoder.encode(a))

  private def decodeFromByteString[A](bytes: ByteString)(implicit decoder: Decoder[A]): A =
    decoder.decode(bytes.toByteArray)
}
