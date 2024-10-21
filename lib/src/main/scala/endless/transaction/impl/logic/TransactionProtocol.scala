package endless.transaction.impl.logic

import cats.data.NonEmptyList
import cats.syntax.either.*
import com.google.protobuf.ByteString
import endless.\/
import endless.core.protocol.{CommandSender, Decoder, Encoder, IncomingCommand}
import endless.protobuf.{ProtobufCommandProtocol, ProtobufDecoder}
import endless.transaction.Transaction.{
  AbortError,
  AbortReason,
  TooLateToAbort,
  TransactionFailed,
  Unknown
}
import endless.transaction.impl.algebra.TransactionAlg
import endless.transaction.impl.algebra.TransactionCreator.AlreadyExists
import endless.transaction.impl.logic.TransactionProtocol.{
  UnexpectedCommandException,
  UnexpectedReplyException
}
import endless.transaction.proto.commands.TransactionCommand.Command
import endless.transaction.proto.commands.*
import endless.transaction.proto.replies.*
import endless.transaction.proto.model
import endless.transaction.{BinaryCodec, Branch, Transaction, proto}

private[transaction] class TransactionProtocol[TID, BID, Q, R](implicit
    tidCodec: BinaryCodec[TID],
    bidCodec: BinaryCodec[BID],
    qCodec: BinaryCodec[Q],
    rCodec: BinaryCodec[R]
) extends ProtobufCommandProtocol[TID, ({ type T[G[_]] = TransactionAlg[G, TID, BID, Q, R] })#T] {

  def server[F[_]]
      : Decoder[IncomingCommand[F, ({ type T[G[_]] = TransactionAlg[G, TID, BID, Q, R] })#T]] =
    ProtobufDecoder[TransactionCommand].map(_.command match {
      case Command.Empty => throw new UnexpectedCommandException
      case Command.Create(CreateCommand(id, query, branches, _)) =>
        handleCommand[F, CreateReply, AlreadyExists.type \/ Unit](
          _.create(
            decodeTransactionID(id),
            decodeFromByteString(query),
            NonEmptyList.fromListUnsafe(branches.map(decodeBranchID).toList)
          ),
          {
            case Left(AlreadyExists) =>
              CreateReply(CreateReply.Reply.AlreadyExists(AlreadyExistsReply()))
            case Right(()) => CreateReply(CreateReply.Reply.Unit(UnitReply()))
          }
        )
      case Command.GetQuery(_) =>
        handleCommand[F, QueryReply, Unknown.type \/ Q](
          _.query,
          {
            case Left(Unknown) => QueryReply(QueryReply.Reply.Unknown(UnknownReply()))
            case Right(query)  => QueryReply(QueryReply.Reply.Query(encodeToByteString(query)))
          }
        )
      case Command.GetBranches(_) =>
        handleCommand[F, BranchesReply, Unknown.type \/ Set[BID]](
          _.branches,
          {
            case Left(Unknown) => BranchesReply(BranchesReply.Reply.Unknown(UnknownReply()))
            case Right(branches) =>
              BranchesReply(
                BranchesReply.Reply.Branches(
                  model.Branches(
                    branches.map(encodeBranchID).toSeq
                  )
                )
              )
          }
        )
      case Command.GetStatus(_) =>
        handleCommand[F, StatusReply, Unknown.type \/ Transaction.Status[R]](
          _.status,
          {
            case Left(Unknown) => StatusReply(StatusReply.Reply.Unknown(UnknownReply()))
            case Right(status) =>
              StatusReply(
                StatusReply.Reply.Status(
                  model.Status(
                    status match {
                      case Transaction.Status.Preparing =>
                        model.Status.Status.Preparing(model.Preparing())
                      case Transaction.Status.Committing =>
                        model.Status.Status.Committing(model.Committing())
                      case Transaction.Status.Committed =>
                        model.Status.Status.Committed(model.Committed())
                      case Transaction.Status.Failed(errors) =>
                        model.Status.Status.Failed(model.Failed(errors.toList))
                      case Transaction.Status.Aborting(reason) =>
                        model.Status.Status.Aborting(model.Aborting(encodeAbortReason(reason)))
                      case Transaction.Status.Aborted(reason) =>
                        model.Status.Status.Aborted(model.Aborted(encodeAbortReason(reason)))
                    }
                  )
                )
              )
          }
        )
      case Command.Abort(AbortCommand(reason, _)) =>
        handleCommand[F, AbortReply, AbortError \/ Unit](
          _.abort(Option.unless(reason.isEmpty)(decodeFromByteString[R](reason))),
          {
            case Left(TooLateToAbort(message)) =>
              AbortReply(
                AbortReply.Reply.Error(
                  model.AbortError(
                    model.AbortError.Error.TooLateToAbort(model.TooLateToAbort(message))
                  )
                )
              )
            case Left(TransactionFailed(message)) =>
              AbortReply(
                AbortReply.Reply.Error(
                  model.AbortError(
                    model.AbortError.Error.TransactionFailed(model.TransactionFailed(message))
                  )
                )
              )
            case Left(Unknown) =>
              AbortReply(
                AbortReply.Reply.Error(
                  model.AbortError(model.AbortError.Error.Unknown(model.Unknown()))
                )
              )
            case Right(()) => AbortReply(AbortReply.Reply.Unit(UnitReply()))
          }
        )
      case Command.BranchVoted(BranchVotedCommand(branchID, vote, _)) =>
        handleCommand[F, UnitReply, Unit](
          _.branchVoted(
            decodeBranchID(branchID),
            vote.vote match {
              case model.Vote.Vote.Commit(_) => Branch.Vote.Commit
              case model.Vote.Vote.Abort(model.Abort(reason, _)) =>
                Branch.Vote.Abort(decodeFromByteString(reason))
              case _ => throw new UnexpectedCommandException
            }
          ),
          _ => UnitReply()
        )
      case Command.BranchCommitted(BranchCommittedCommand(branchId, _)) =>
        handleCommand[F, UnitReply, Unit](
          _.branchCommitted(decodeBranchID(branchId)),
          _ => UnitReply()
        )
      case Command.BranchAborted(BranchAbortedCommand(branchId, _)) =>
        handleCommand[F, UnitReply, Unit](
          _.branchAborted(decodeBranchID(branchId)),
          _ => UnitReply()
        )
      case Command.BranchFailed(BranchFailedCommand(branchId, error, _)) =>
        handleCommand[F, UnitReply, Unit](
          _.branchFailed(decodeBranchID(branchId), error),
          _ => UnitReply()
        )
      case Command.TransactionTimeout(_) =>
        handleCommand[F, UnitReply, Unit](_.timeout(), _ => UnitReply())
    })

  def clientFor[F[_]](tid: TID)(implicit
      sender: CommandSender[F, TID]
  ): TransactionAlg[F, TID, BID, Q, R] = new TransactionAlg[F, TID, BID, Q, R] {
    def create(id: TID, query: Q, branches: NonEmptyList[BID]): F[AlreadyExists.type \/ Unit] =
      sendCommand[F, TransactionCommand, CreateReply, AlreadyExists.type \/ Unit](
        id,
        TransactionCommand.of(
          Command.Create(
            CreateCommand(
              encodeTransactionID(id),
              encodeToByteString(query),
              branches.map(encodeBranchID).toList
            )
          )
        ),
        {
          case CreateReply(CreateReply.Reply.AlreadyExists(_), _) =>
            AlreadyExists.asLeft
          case CreateReply(CreateReply.Reply.Unit(_), _) =>
            ().asRight
          case CreateReply(CreateReply.Reply.Empty, _) =>
            throw new UnexpectedReplyException
        }
      )

    def query: F[Unknown.type \/ Q] =
      sendCommand[F, TransactionCommand, QueryReply, Unknown.type \/ Q](
        tid,
        TransactionCommand.of(Command.GetQuery(GetQueryCommand())),
        {
          case QueryReply(QueryReply.Reply.Unknown(_), _) => Unknown.asLeft
          case QueryReply(QueryReply.Reply.Query(query), _) =>
            decodeFromByteString[Q](query).asRight
          case QueryReply(QueryReply.Reply.Empty, _) =>
            throw new UnexpectedReplyException
        }
      )

    def branches: F[Unknown.type \/ Set[BID]] =
      sendCommand[F, TransactionCommand, BranchesReply, Unknown.type \/ Set[BID]](
        tid,
        TransactionCommand.of(Command.GetBranches(GetBranchesCommand())),
        {
          case BranchesReply(BranchesReply.Reply.Unknown(_), _) =>
            Unknown.asLeft
          case BranchesReply(BranchesReply.Reply.Branches(branches), _) =>
            branches.branches.map(decodeBranchID).toSet.asRight
          case BranchesReply(BranchesReply.Reply.Empty, _) =>
            throw new UnexpectedReplyException
        }
      )

    def status: F[Unknown.type \/ Transaction.Status[R]] =
      sendCommand[F, TransactionCommand, StatusReply, Unknown.type \/ Transaction.Status[
        R
      ]](
        tid,
        TransactionCommand.of(Command.GetStatus(GetStatusCommand())),
        {
          case StatusReply(StatusReply.Reply.Unknown(_), _) =>
            Unknown.asLeft
          case StatusReply(StatusReply.Reply.Status(status), _) =>
            status.status match {
              case model.Status.Status.Preparing(_) =>
                Transaction.Status.Preparing.asRight
              case model.Status.Status.Committing(_) =>
                Transaction.Status.Committing.asRight
              case model.Status.Status.Committed(_) =>
                Transaction.Status.Committed.asRight
              case model.Status.Status.Failed(model.Failed(errors, _)) =>
                Transaction.Status.Failed(NonEmptyList.fromListUnsafe(errors.toList)).asRight
              case model.Status.Status.Aborting(model.Aborting(reason, _)) =>
                Transaction.Status.Aborting(decodeAbortReason(reason)).asRight
              case model.Status.Status.Aborted(model.Aborted(reason, _)) =>
                Transaction.Status.Aborted(decodeAbortReason(reason)).asRight
              case _ => throw new UnexpectedCommandException
            }
          case StatusReply(StatusReply.Reply.Empty, _) =>
            throw new UnexpectedReplyException
        }
      )

    def abort(reason: Option[R]): F[AbortError \/ Unit] =
      sendCommand[F, TransactionCommand, AbortReply, AbortError \/ Unit](
        tid,
        TransactionCommand.of(
          Command.Abort(AbortCommand(reason.map(encodeToByteString[R]).getOrElse(ByteString.EMPTY)))
        ),
        {
          case AbortReply(AbortReply.Reply.Error(error), _) =>
            error.error match {
              case model.AbortError.Error.TooLateToAbort(tooLate) =>
                TooLateToAbort(tooLate.message).asLeft
              case model.AbortError.Error.TransactionFailed(failed) =>
                TransactionFailed(failed.message).asLeft
              case model.AbortError.Error.Unknown(_) =>
                Unknown.asLeft
              case model.AbortError.Error.Empty => throw new UnexpectedReplyException
            }
          case AbortReply(AbortReply.Reply.Unit(_), _) =>
            ().asRight
          case AbortReply(AbortReply.Reply.Empty, _) =>
            throw new UnexpectedReplyException
        }
      )

    def branchVoted(branch: BID, vote: Branch.Vote[R]): F[Unit] =
      sendCommand[F, TransactionCommand, UnitReply, Unit](
        tid,
        TransactionCommand.of(
          Command.BranchVoted(
            BranchVotedCommand(
              encodeBranchID(branch),
              model.Vote(vote match {
                case Branch.Vote.Commit => model.Vote.Vote.Commit(model.Commit())
                case Branch.Vote.Abort(reason) =>
                  model.Vote.Vote.Abort(model.Abort(encodeToByteString(reason)))
              })
            )
          )
        ),
        _ => ()
      )

    def branchCommitted(branch: BID): F[Unit] =
      sendCommand[F, TransactionCommand, UnitReply, Unit](
        tid,
        TransactionCommand.of(
          Command.BranchCommitted(BranchCommittedCommand(encodeBranchID(branch)))
        ),
        _ => ()
      )

    def branchAborted(branch: BID): F[Unit] =
      sendCommand[F, TransactionCommand, UnitReply, Unit](
        tid,
        TransactionCommand.of(Command.BranchAborted(BranchAbortedCommand(encodeBranchID(branch)))),
        _ => ()
      )

    def branchFailed(branch: BID, error: String): F[Unit] =
      sendCommand[F, TransactionCommand, UnitReply, Unit](
        tid,
        TransactionCommand.of(
          Command.BranchFailed(BranchFailedCommand(encodeBranchID(branch), error))
        ),
        _ => ()
      )

    def timeout(): F[Unit] = sendCommand[F, TransactionCommand, UnitReply, Unit](
      id,
      TransactionCommand.of(Command.TransactionTimeout(TransactionTimeoutCommand())),
      _ => ()
    )

    def id: TID = tid
  }

  private def encodeAbortReason(reason: AbortReason[R]) = {
    reason match {
      case AbortReason.Timeout =>
        model.AbortReason(
          model.AbortReason.Reason.Timeout(model.TimeoutAbortReason())
        )
      case AbortReason.Branches(reasons) =>
        model.AbortReason(
          model.AbortReason.Reason.BranchesAborted(
            model.BranchesAbortReason(reasons.map(encodeToByteString[R]).toList)
          )
        )
      case AbortReason.Client(reason) =>
        model.AbortReason(
          model.AbortReason.Reason.ClientAborted(
            model.ClientAbortReason(reason.map(encodeToByteString[R]).getOrElse(ByteString.EMPTY))
          )
        )
    }
  }

  private def decodeAbortReason(reason: model.AbortReason) = {
    reason.reason match {
      case model.AbortReason.Reason.Timeout(_) => AbortReason.Timeout
      case model.AbortReason.Reason.BranchesAborted(model.BranchesAbortReason(reasons, _))
          if reasons.nonEmpty =>
        AbortReason.Branches(
          NonEmptyList.fromListUnsafe(reasons.map(decodeFromByteString[R]).toList)
        )
      case model.AbortReason.Reason.ClientAborted(model.ClientAbortReason(reason, _)) =>
        AbortReason.Client(Option.unless(reason.isEmpty)(decodeFromByteString[R](reason)))
      case _ => throw new UnexpectedReplyException
    }
  }

  private def encodeBranchID(branch: BID) = proto.model.BranchID(encodeToByteString(branch))

  private def decodeBranchID(branch: proto.model.BranchID) = decodeFromByteString[BID](branch.value)

  private def decodeTransactionID(transactionID: proto.model.TransactionID) =
    decodeFromByteString[TID](transactionID.value)

  private def encodeTransactionID(transactionID: TID) =
    proto.model.TransactionID(encodeToByteString(transactionID))

  private def encodeToByteString[A](a: A)(implicit encoder: Encoder[A]): ByteString =
    ByteString.copyFrom(encoder.encode(a))

  private def decodeFromByteString[A](bytes: ByteString)(implicit decoder: Decoder[A]): A =
    decoder.decode(bytes.toByteArray)

}

private[transaction] object TransactionProtocol {
  final class UnexpectedCommandException extends RuntimeException("Unexpected command")
  final class UnexpectedReplyException extends RuntimeException("Unexpected reply")
}
