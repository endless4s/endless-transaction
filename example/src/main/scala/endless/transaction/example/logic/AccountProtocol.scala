package endless.transaction.example.logic

import endless.\/
import endless.core.protocol.{CommandSender, Decoder, IncomingCommand}
import endless.protobuf.{ProtobufCommandProtocol, ProtobufDecoder}
import endless.transaction.example.algebra.Account
import endless.transaction.example.algebra.Account.*
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.data.{AccountID, NonNegAmount, PosAmount, Transfer}
import endless.transaction.example.proto.commands.{
  AbortTransferCommand,
  AccountCommand,
  BalanceCommand,
  CommitTransferCommand,
  DepositCommand,
  OpenCommand,
  PrepareIncomingTransferCommand,
  PrepareOutgoingTransferCommand,
  WithdrawCommand
}
import endless.transaction.example.proto.commands.AccountCommand.Command
import endless.transaction.example.proto.replies.{AlreadyExistsReply, OpenReply, UnitReply}
import endless.transaction.example.proto.replies
import endless.transaction.example.proto.model
import cats.syntax.either.*
import endless.transaction.example.logic.AccountProtocol.{
  UnexpectedCommandException,
  UnexpectedReplyException
}
import cats.syntax.show.*
import java.util.UUID

class AccountProtocol extends ProtobufCommandProtocol[AccountID, Account] {

  def server[F[_]]: Decoder[IncomingCommand[F, Account]] =
    ProtobufDecoder[AccountCommand].map(_.command match {
      case Command.Empty => throw new UnexpectedCommandException
      case Command.Open(_) =>
        handleCommand[F, OpenReply, AlreadyExists.type \/ Unit](
          _.open,
          {
            case Left(AlreadyExists) =>
              OpenReply(OpenReply.Reply.AlreadyExists(AlreadyExistsReply()))
            case Right(_) => OpenReply(OpenReply.Reply.Unit(UnitReply()))
          }
        )
      case Command.Balance(BalanceCommand(_)) =>
        handleCommand[F, replies.BalanceReply, Unknown.type \/ NonNegAmount](
          _.balance,
          {
            case Left(Unknown) =>
              replies.BalanceReply(replies.BalanceReply.Reply.Unknown(replies.UnknownReply()))
            case Right(amount) =>
              replies.BalanceReply(replies.BalanceReply.Reply.Amount(amount.value))
          }
        )
      case Command.Deposit(DepositCommand(amount, _)) =>
        handleCommand[F, replies.DepositReply, Unknown.type \/ PosAmount](
          _.deposit(PosAmount(amount)),
          {
            case Left(Unknown) =>
              replies.DepositReply(replies.DepositReply.Reply.Unknown(replies.UnknownReply()))
            case Right(amount) =>
              replies.DepositReply(replies.DepositReply.Reply.Amount(amount.value))
          }
        )
      case Command.Withdraw(WithdrawCommand(value, _)) =>
        handleCommand[F, replies.WithdrawReply, WithdrawFailure \/ NonNegAmount](
          _.withdraw(PosAmount(value)),
          {
            case Left(Unknown) =>
              replies.WithdrawReply(replies.WithdrawReply.Reply.Unknown(replies.UnknownReply()))
            case Left(InsufficientFunds(missing)) =>
              replies.WithdrawReply(
                replies.WithdrawReply.Reply.InsufficientFunds(
                  model.InsufficientFunds(missing.value)
                )
              )
            case Left(PendingOutgoingTransfer) =>
              replies.WithdrawReply(
                replies.WithdrawReply.Reply.PendingOutgoingTransfer(
                  model.PendingOutgoingTransfer()
                )
              )
            case Right(amount) =>
              replies.WithdrawReply(replies.WithdrawReply.Reply.Amount(amount.value))
          }
        )
      case Command.PrepareOutgoingTransfer(
            PrepareOutgoingTransferCommand(
              model.TransferID(transferID, _),
              model
                .Transfer(model.AccountID(origin, _), model.AccountID(destination, _), amount, _),
              _
            )
          ) =>
        handleCommand[F, replies.PrepareOutgoingTransferReply, WithdrawFailure \/ Unit](
          _.prepareOutgoingTransfer(
            TransferID(UUID.fromString(transferID)),
            Transfer(AccountID(origin), AccountID(destination), PosAmount(amount))
          ),
          {
            case Left(Unknown) =>
              replies.PrepareOutgoingTransferReply(
                replies.PrepareOutgoingTransferReply.Reply.Unknown(replies.UnknownReply())
              )
            case Left(InsufficientFunds(missing)) =>
              replies.PrepareOutgoingTransferReply(
                replies.PrepareOutgoingTransferReply.Reply.InsufficientFunds(
                  model.InsufficientFunds(missing.value)
                )
              )
            case Left(PendingOutgoingTransfer) =>
              replies.PrepareOutgoingTransferReply(
                replies.PrepareOutgoingTransferReply.Reply.PendingOutgoingTransfer(
                  model.PendingOutgoingTransfer()
                )
              )
            case Right(_) =>
              replies.PrepareOutgoingTransferReply(
                replies.PrepareOutgoingTransferReply.Reply.Unit(UnitReply())
              )
          }
        )
      case Command.PrepareIncomingTransfer(
            PrepareIncomingTransferCommand(
              model.TransferID(transferID, _),
              model
                .Transfer(model.AccountID(origin, _), model.AccountID(destination, _), amount, _),
              _
            )
          ) =>
        handleCommand[F, replies.PrepareIncomingTransferReply, Unknown.type \/ Unit](
          _.prepareIncomingTransfer(
            TransferID(UUID.fromString(transferID)),
            Transfer(AccountID(origin), AccountID(destination), PosAmount(amount))
          ),
          {
            case Left(Unknown) =>
              replies.PrepareIncomingTransferReply(
                replies.PrepareIncomingTransferReply.Reply.Unknown(replies.UnknownReply())
              )
            case Right(_) =>
              replies.PrepareIncomingTransferReply(
                replies.PrepareIncomingTransferReply.Reply.Unit(UnitReply())
              )
          }
        )
      case Command.CommitTransfer(CommitTransferCommand(model.TransferID(transferID, _), _)) =>
        handleCommand[F, replies.CommitTransferReply, TransferFailure \/ Unit](
          _.commitTransfer(TransferID(UUID.fromString(transferID))),
          {
            case Left(TransferUnknown(id)) =>
              replies.CommitTransferReply(
                replies.CommitTransferReply.Reply.TransferUnknown(
                  replies.TransferUnknown(
                    model.TransferID(id.value.show)
                  )
                )
              )
            case Left(Unknown) =>
              replies.CommitTransferReply(
                replies.CommitTransferReply.Reply.Unknown(replies.UnknownReply())
              )
            case Right(_) =>
              replies.CommitTransferReply(
                replies.CommitTransferReply.Reply.Unit(UnitReply())
              )
          }
        )

      case Command.AbortTransfer(AbortTransferCommand(model.TransferID(transferID, _), _)) =>
        handleCommand[F, replies.AbortTransferReply, TransferFailure \/ Unit](
          _.abortTransfer(TransferID(UUID.fromString(transferID))),
          {
            case Left(TransferUnknown(id)) =>
              replies.AbortTransferReply(
                replies.AbortTransferReply.Reply.TransferUnknown(
                  replies.TransferUnknown(
                    model.TransferID(id.value.show)
                  )
                )
              )
            case Left(Unknown) =>
              replies.AbortTransferReply(
                replies.AbortTransferReply.Reply.Unknown(replies.UnknownReply())
              )
            case Right(_) =>
              replies.AbortTransferReply(
                replies.AbortTransferReply.Reply.Unit(UnitReply())
              )
          }
        )
    })

  def clientFor[F[_]](id: AccountID)(implicit sender: CommandSender[F, AccountID]): Account[F] =
    new Account[F] {
      def open: F[AlreadyExists.type \/ Unit] =
        sendCommand[F, AccountCommand, OpenReply, AlreadyExists.type \/ Unit](
          id,
          AccountCommand(AccountCommand.Command.Open(OpenCommand())),
          {
            case OpenReply(OpenReply.Reply.AlreadyExists(_), _) => AlreadyExists.asLeft
            case OpenReply(OpenReply.Reply.Unit(_), _)          => ().asRight
            case OpenReply(OpenReply.Reply.Empty, _) => throw new UnexpectedReplyException
          }
        )

      def balance: F[Account.Unknown.type \/ NonNegAmount] =
        sendCommand[F, AccountCommand, replies.BalanceReply, Unknown.type \/ NonNegAmount](
          id,
          AccountCommand(AccountCommand.Command.Balance(BalanceCommand())),
          {
            case replies.BalanceReply(replies.BalanceReply.Reply.Unknown(_), _) => Unknown.asLeft
            case replies.BalanceReply(replies.BalanceReply.Reply.Amount(amount), _) =>
              NonNegAmount(amount).asRight
            case replies.BalanceReply(replies.BalanceReply.Reply.Empty, _) =>
              throw new UnexpectedReplyException
          }
        )

      def prepareOutgoingTransfer(
          transferID: TransferID,
          transfer: Transfer
      ): F[WithdrawFailure \/ Unit] =
        sendCommand[
          F,
          AccountCommand,
          replies.PrepareOutgoingTransferReply,
          WithdrawFailure \/ Unit
        ](
          id,
          AccountCommand(
            AccountCommand.Command.PrepareOutgoingTransfer(
              PrepareOutgoingTransferCommand(
                model.TransferID(transferID.value.show),
                model.Transfer(
                  model.AccountID(transfer.origin.value),
                  model.AccountID(transfer.destination.value),
                  transfer.amount.value
                )
              )
            )
          ),
          {
            case replies.PrepareOutgoingTransferReply(
                  replies.PrepareOutgoingTransferReply.Reply.Unknown(_),
                  _
                ) =>
              Unknown.asLeft
            case replies.PrepareOutgoingTransferReply(
                  replies.PrepareOutgoingTransferReply.Reply.InsufficientFunds(
                    model.InsufficientFunds(missing, _)
                  ),
                  _
                ) =>
              InsufficientFunds(PosAmount(missing)).asLeft
            case replies.PrepareOutgoingTransferReply(
                  replies.PrepareOutgoingTransferReply.Reply.PendingOutgoingTransfer(_),
                  _
                ) =>
              PendingOutgoingTransfer.asLeft
            case replies.PrepareOutgoingTransferReply(
                  replies.PrepareOutgoingTransferReply.Reply.Unit(_),
                  _
                ) =>
              ().asRight
            case replies.PrepareOutgoingTransferReply(
                  replies.PrepareOutgoingTransferReply.Reply.Empty,
                  _
                ) =>
              throw new UnexpectedReplyException
          }
        )

      def prepareIncomingTransfer(
          transferID: TransferID,
          transfer: Transfer
      ): F[Unknown.type \/ Unit] =
        sendCommand[
          F,
          AccountCommand,
          replies.PrepareIncomingTransferReply,
          Unknown.type \/ Unit
        ](
          id,
          AccountCommand(
            AccountCommand.Command.PrepareIncomingTransfer(
              PrepareIncomingTransferCommand(
                model.TransferID(transferID.value.show),
                model.Transfer(
                  model.AccountID(transfer.origin.value),
                  model.AccountID(transfer.destination.value),
                  transfer.amount.value
                )
              )
            )
          ),
          {
            case replies.PrepareIncomingTransferReply(
                  replies.PrepareIncomingTransferReply.Reply.Unknown(_),
                  _
                ) =>
              Unknown.asLeft
            case replies.PrepareIncomingTransferReply(
                  replies.PrepareIncomingTransferReply.Reply.Unit(_),
                  _
                ) =>
              ().asRight
            case replies.PrepareIncomingTransferReply(
                  replies.PrepareIncomingTransferReply.Reply.Empty,
                  _
                ) =>
              throw new UnexpectedReplyException
          }
        )

      def commitTransfer(transferID: TransferID): F[TransferFailure \/ Unit] =
        sendCommand[F, AccountCommand, replies.CommitTransferReply, TransferFailure \/ Unit](
          id,
          AccountCommand(
            AccountCommand.Command.CommitTransfer(
              CommitTransferCommand(model.TransferID(transferID.value.show))
            )
          ),
          {
            case replies.CommitTransferReply(
                  replies.CommitTransferReply.Reply.TransferUnknown(
                    replies.TransferUnknown(model.TransferID(transferID, _), _)
                  ),
                  _
                ) =>
              TransferUnknown(TransferID(UUID.fromString(transferID))).asLeft
            case replies.CommitTransferReply(
                  replies.CommitTransferReply.Reply.Unknown(_),
                  _
                ) =>
              Unknown.asLeft
            case replies.CommitTransferReply(
                  replies.CommitTransferReply.Reply.Unit(_),
                  _
                ) =>
              ().asRight
            case replies.CommitTransferReply(
                  replies.CommitTransferReply.Reply.Empty,
                  _
                ) =>
              throw new UnexpectedReplyException
          }
        )

      def abortTransfer(transferID: TransferID): F[TransferFailure \/ Unit] =
        sendCommand[F, AccountCommand, replies.AbortTransferReply, TransferFailure \/ Unit](
          id,
          AccountCommand(
            AccountCommand.Command.AbortTransfer(
              AbortTransferCommand(model.TransferID(transferID.value.show))
            )
          ),
          {
            case replies.AbortTransferReply(
                  replies.AbortTransferReply.Reply.TransferUnknown(
                    replies.TransferUnknown(model.TransferID(transferID, _), _)
                  ),
                  _
                ) =>
              TransferUnknown(TransferID(UUID.fromString(transferID))).asLeft
            case replies.AbortTransferReply(replies.AbortTransferReply.Reply.Unknown(_), _) =>
              Unknown.asLeft
            case replies.AbortTransferReply(replies.AbortTransferReply.Reply.Unit(_), _) =>
              ().asRight
            case replies.AbortTransferReply(replies.AbortTransferReply.Reply.Empty, _) =>
              throw new UnexpectedReplyException
          }
        )

      def deposit(amount: PosAmount): F[Account.Unknown.type \/ PosAmount] =
        sendCommand[F, AccountCommand, replies.DepositReply, Unknown.type \/ PosAmount](
          id,
          AccountCommand(AccountCommand.Command.Deposit(DepositCommand(amount.value))),
          {
            case replies.DepositReply(replies.DepositReply.Reply.Unknown(_), _) =>
              Unknown.asLeft
            case replies.DepositReply(replies.DepositReply.Reply.Amount(amount), _) =>
              PosAmount(amount).asRight
            case replies.DepositReply(replies.DepositReply.Reply.Empty, _) =>
              throw new UnexpectedReplyException
          }
        )

      def withdraw(amount: PosAmount): F[WithdrawFailure \/ NonNegAmount] =
        sendCommand[F, AccountCommand, replies.WithdrawReply, WithdrawFailure \/ NonNegAmount](
          id,
          AccountCommand(AccountCommand.Command.Withdraw(WithdrawCommand(amount.value))),
          {
            case replies.WithdrawReply(replies.WithdrawReply.Reply.Unknown(_), _) =>
              Unknown.asLeft
            case replies.WithdrawReply(
                  replies.WithdrawReply.Reply
                    .InsufficientFunds(model.InsufficientFunds(missing, _)),
                  _
                ) =>
              InsufficientFunds(PosAmount(missing)).asLeft
            case replies.WithdrawReply(
                  replies.WithdrawReply.Reply.PendingOutgoingTransfer(_),
                  _
                ) =>
              PendingOutgoingTransfer.asLeft
            case replies.WithdrawReply(replies.WithdrawReply.Reply.Amount(amount), _) =>
              NonNegAmount(amount).asRight
            case replies.WithdrawReply(replies.WithdrawReply.Reply.Empty, _) =>
              throw new UnexpectedReplyException
          }
        )
    }
}

object AccountProtocol {
  final class UnexpectedCommandException extends RuntimeException("Unexpected command")
  final class UnexpectedReplyException extends RuntimeException("Unexpected reply")
}
