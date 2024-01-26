package endless.transaction.example.data

sealed trait AccountEvent

object AccountEvent {
  final object Opened extends AccountEvent
  final case class Deposited(amount: PosAmount) extends AccountEvent
  final case class Withdrawn(amount: PosAmount) extends AccountEvent
  final case class OutgoingTransferPrepared(id: Transfer.TransferID, amount: PosAmount)
      extends AccountEvent
  final case class IncomingTransferPrepared(id: Transfer.TransferID, amount: PosAmount)
      extends AccountEvent
  final case class TransferCommitted(id: Transfer.TransferID) extends AccountEvent
  final case class TransferAborted(id: Transfer.TransferID) extends AccountEvent
}
