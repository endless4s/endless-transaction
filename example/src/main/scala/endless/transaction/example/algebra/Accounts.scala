package endless.transaction.example.algebra

import endless.\/
import endless.transaction.BinaryCodec
import endless.transaction.example.algebra.Accounts.TransferFailure
import endless.transaction.example.data.{AccountID, PosAmount}
import endless.transaction.example.proto

trait Accounts[F[_]] {
  def accountFor(name: AccountID): Account[F]
  def transfer(from: AccountID, to: AccountID, amount: PosAmount): F[TransferFailure \/ Unit]
}

object Accounts {
  sealed trait TransferFailure
  object TransferFailure {
    final case class InsufficientFunds(missing: PosAmount) extends TransferFailure
    final case class AccountNotFound(accountID: AccountID) extends TransferFailure
    object OtherPendingTransfer extends TransferFailure
    object Timeout extends TransferFailure

    implicit val binaryCodec: endless.transaction.BinaryCodec[TransferFailure] =
      new BinaryCodec[TransferFailure] {
        def decode(payload: Array[Byte]): TransferFailure =
          proto.model.TransferFailure.parseFrom(payload) match {
            case proto.model.TransferFailure(
                  proto.model.TransferFailure.Reason
                    .InsufficientFunds(proto.model.InsufficientFunds(missing, _)),
                  _
                ) =>
              InsufficientFunds(PosAmount(missing))
            case proto.model.TransferFailure(
                  proto.model.TransferFailure.Reason
                    .NotFound(proto.model.NotFound(accountID, _)),
                  _
                ) =>
              AccountNotFound(AccountID(accountID.id))
            case proto.model
                  .TransferFailure(proto.model.TransferFailure.Reason.OtherPendingTransfer(_), _) =>
              OtherPendingTransfer
            case proto.model.TransferFailure(proto.model.TransferFailure.Reason.Timeout(_), _) =>
              Timeout
            case proto.model.TransferFailure(proto.model.TransferFailure.Reason.Empty, _) =>
              throw new IllegalArgumentException("Invalid payload")
          }

        def encode(a: TransferFailure): Array[Byte] = proto.model
          .TransferFailure(
            a match {
              case InsufficientFunds(missing) =>
                proto.model.TransferFailure.Reason
                  .InsufficientFunds(proto.model.InsufficientFunds(missing.value))
              case AccountNotFound(accountID) =>
                proto.model.TransferFailure.Reason.NotFound(
                  proto.model.NotFound(proto.model.AccountID(accountID.value))
                )
              case OtherPendingTransfer =>
                proto.model.TransferFailure.Reason.OtherPendingTransfer(
                  proto.model.OtherPendingTransfer()
                )
              case Timeout => proto.model.TransferFailure.Reason.Timeout(proto.model.Timeout())
            }
          )
          .toByteArray
      }
  }
}
