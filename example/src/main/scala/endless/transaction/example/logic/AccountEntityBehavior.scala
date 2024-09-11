package endless.transaction.example.logic

import cats.syntax.applicative.*
import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.show.*
import cats.syntax.functor.*
import cats.syntax.flatMap.*
import endless.\/
import endless.core.entity.Entity
import endless.transaction.example.algebra.Account
import Account.*
import endless.transaction.example.data.AccountEvent.*
import endless.transaction.example.data.*
import org.typelevel.log4cats.Logger

final case class AccountEntityBehavior[F[_]: Logger](entity: Entity[F, AccountState, AccountEvent])
    extends Account[F] {
  import entity.*

  def open: F[Account.AlreadyExists.type \/ Unit] = ifUnknownF[AlreadyExists.type, Unit](
    write(Opened)
  )(_ => AlreadyExists)

  def balance: F[Unknown.type \/ NonNegAmount] = ifKnown(_.balance)(Unknown)

  def prepareOutgoingTransfer(
      id: Transfer.TransferID,
      transfer: Transfer
  ): F[WithdrawFailure \/ Unit] =
    ifKnownFE[WithdrawFailure, Unit](state =>
      if (state.pendingOutgoingTransfer.exists(_.id === id) || state.transferHistory.contains(id))
        Logger[F].debug(show"Outgoing transfer already prepared: $id ($state)").map(_.asRight)
      else if (state.pendingOutgoingTransfer.isDefined) {
        Logger[F].warn(
          show"Account already has pending transfer, cannot prepare $transfer ($state)"
        ) >> (PendingOutgoingTransfer: WithdrawFailure).asLeft.pure
      } else if (state.balance >= transfer.amount)
        Logger[F].debug(
          show"Account has enough balance, preparing transfer $id to account ${transfer.destination} ($state)"
        ) >> write(
          OutgoingTransferPrepared(id, transfer.amount)
        ).map(_.asRight)
      else
        Logger[F].warn(
          show"Not enough balance on account for transfer $id: $transfer ($state)"
        ) >> (InsufficientFunds(transfer.amount - state.balance): WithdrawFailure).asLeft.pure
    )(Unknown)

  def prepareIncomingTransfer(
      id: Transfer.TransferID,
      transfer: Transfer
  ): F[Unknown.type \/ Unit] =
    ifKnownFE[Unknown.type, Unit](state =>
      if (state.pendingIncomingTransfers.exists(_.id === id) || state.transferHistory.contains(id))
        Logger[F].debug(show"Incoming transfer already prepared: $id ($state)").map(_.asRight)
      else
        Logger[F].debug(
          show"Prepare incoming transfer $id from account ${transfer.origin} ($state)"
        ) >> write(
          IncomingTransferPrepared(id, transfer.amount)
        ).map(_.asRight)
    )(Unknown)

  def commitTransfer(id: Transfer.TransferID): F[TransferFailure \/ Unit] =
    ifKnownFE[TransferFailure, Unit](state =>
      if (state.transferHistory.contains(id)) {
        Logger[F].debug(show"Transfer $id already committed ($state)") >> ().asRight.pure
      } else if (state.getPendingTransfer(id).nonEmpty)
        Logger[F].debug(show"Committing transfer $id ($state)") >> write(TransferCommitted(id))
          .map(_.asRight)
      else
        Logger[F].error(show"Unprepared transfer $id ($state)") >> (TransferUnknown(
          id
        ): TransferFailure).asLeft.pure
    )(Unknown)

  def abortTransfer(id: Transfer.TransferID): F[TransferFailure \/ Unit] =
    ifKnownFE[TransferFailure, Unit](state =>
      if (state.transferHistory.contains(id)) {
        Logger[F].debug(show"Transfer $id already aborted ($state)") >> ().asRight.pure
      } else if (state.getPendingTransfer(id).nonEmpty)
        Logger[F].debug(show"Aborting transfer $id ($state)") >> write(TransferAborted(id))
          .map(_.asRight)
      else
        Logger[F].error(show"Unprepared transfer $id ($state)") >> (TransferUnknown(
          id
        ): TransferFailure).asLeft.pure
    )(Unknown)

  def withdraw(amount: PosAmount): F[WithdrawFailure \/ NonNegAmount] =
    ifKnownFE[WithdrawFailure, NonNegAmount](state =>
      if (state.pendingOutgoingTransfer.isDefined) {
        Logger[F].warn(
          show"Account has pending outgoing transfer, cannot withdraw $amount ($state)"
        ) >> (PendingOutgoingTransfer: WithdrawFailure).asLeft.pure
      } else if (state.balance >= amount)
        Logger[F].debug(show"Withdrawing $amount ($state)") >> write(Withdrawn(amount))
          .as((state.balance - amount).asRight)
      else
        (InsufficientFunds(amount - state.balance): WithdrawFailure).asLeft.pure
    )(Unknown)

  def deposit(amount: PosAmount): F[Unknown.type \/ PosAmount] =
    ifKnownF(state =>
      Logger[F].debug(show"Depositing $amount ($state)") >> write(Deposited(amount)).as(
        state.balance + amount
      )
    )(
      Unknown
    )

}
