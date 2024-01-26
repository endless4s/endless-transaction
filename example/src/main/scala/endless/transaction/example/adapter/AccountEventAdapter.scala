package endless.transaction.example.adapter

import endless.transaction.example.data.{AccountEvent, PosAmount}
import endless.transaction.example.proto.events as proto
import endless.transaction.example.proto.model
import endless.transaction.example.data.AccountEvent.*
import endless.transaction.example.data.Transfer.TransferID

import java.util.UUID

class AccountEventAdapter {
  def toJournal(event: AccountEvent): proto.AccountEvent =
    event match {
      case Opened            => proto.AccountOpened()
      case Deposited(amount) => proto.Deposited(amount.value)
      case Withdrawn(amount) => proto.Withdrawn(amount.value)
      case OutgoingTransferPrepared(id, amount) =>
        proto.OutgoingTransferPrepared(model.TransferID(id.value.toString), amount.value)
      case IncomingTransferPrepared(id, amount) =>
        proto.IncomingTransferPrepared(model.TransferID(id.value.toString), amount.value)
      case TransferCommitted(id) => proto.TransferCommitted(model.TransferID(id.value.toString))
      case TransferAborted(id)   => proto.TransferAborted(model.TransferID(id.value.toString))
    }

  def fromJournal(event: proto.AccountEvent): AccountEvent = event match {
    case proto.AccountOpened(_)     => Opened
    case proto.Deposited(amount, _) => Deposited(PosAmount(amount))
    case proto.Withdrawn(amount, _) => Withdrawn(PosAmount(amount))
    case proto.OutgoingTransferPrepared(model.TransferID(id, _), amount, _) =>
      OutgoingTransferPrepared(TransferID(UUID.fromString(id)), PosAmount(amount))
    case proto.IncomingTransferPrepared(model.TransferID(id, _), amount, _) =>
      IncomingTransferPrepared(TransferID(UUID.fromString(id)), PosAmount(amount))
    case proto.TransferCommitted(model.TransferID(id, _), _) =>
      TransferCommitted(TransferID(UUID.fromString(id)))
    case proto.TransferAborted(model.TransferID(id, _), _) =>
      TransferAborted(TransferID(UUID.fromString(id)))
  }
}
