package endless.transaction.example.algebra

import endless.\/
import endless.transaction.example.algebra.Account.{
  AlreadyExists,
  IncomingTransferFailure,
  TransferFailure,
  Unknown,
  WithdrawFailure
}
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.data.{NonNegAmount, PosAmount, Transfer}
import cats.syntax.show.*

trait Account[F[_]] {
  def open: F[AlreadyExists.type \/ Unit]
  def balance: F[Unknown.type \/ NonNegAmount]
  def prepareOutgoingTransfer(id: TransferID, transfer: Transfer): F[WithdrawFailure \/ Unit]
  def prepareIncomingTransfer(
      id: TransferID,
      transfer: Transfer
  ): F[IncomingTransferFailure \/ Unit]
  def commitTransfer(id: TransferID): F[TransferFailure \/ Unit]
  def abortTransfer(id: TransferID): F[TransferFailure \/ Unit]
  def deposit(amount: PosAmount): F[Unknown.type \/ PosAmount]
  def withdraw(amount: PosAmount): F[WithdrawFailure \/ NonNegAmount]
}

object Account {
  object Unknown extends WithdrawFailure with TransferFailure with IncomingTransferFailure {
    def message: String = "Account is unknown"
  }

  sealed trait WithdrawFailure
  final case class InsufficientFunds(missing: PosAmount) extends WithdrawFailure
  object PendingOutgoingTransfer extends WithdrawFailure

  sealed trait IncomingTransferFailure
  object PendingIncomingTransfer extends IncomingTransferFailure

  sealed trait TransferFailure {
    def message: String
  }
  final case class TransferUnknown(id: TransferID) extends TransferFailure {
    def message: String = show"Transfer $id is unknown"
  }

  object AlreadyExists
}
