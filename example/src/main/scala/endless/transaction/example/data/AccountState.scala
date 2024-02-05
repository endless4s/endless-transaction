package endless.transaction.example.data
import cats.syntax.eq.*
import endless.transaction.example.data.AccountState.PendingTransfer

final case class AccountState(
    balance: NonNegAmount,
    pendingTransfer: Option[PendingTransfer] = None,
    transferHistory: Set[Transfer.TransferID] = Set.empty
) {
  def abortTransfer(id: Transfer.TransferID): AccountState = {
    if (transferHistory.contains(id)) this
    else {
      require(pendingTransfer.exists(_.id === id), "Transfer is unknown")
      copy(pendingTransfer = None, transferHistory = transferHistory + id)
    }
  }

  def commitTransfer(id: Transfer.TransferID): AccountState = {
    if (transferHistory.contains(id)) this
    else {
      require(pendingTransfer.exists(_.id === id), "Transfer is unknown")
      copy(
        pendingTransfer = None,
        balance = pendingTransfer match {
          case Some(PendingTransfer.Incoming(`id`, amount)) => balance + amount
          case Some(PendingTransfer.Outgoing(`id`, amount)) => balance - amount
          case _                                            => balance
        },
        transferHistory = transferHistory + id
      )
    }
  }

  def prepareOutgoingTransfer(id: Transfer.TransferID, amount: PosAmount): AccountState = {
    if (transferHistory.contains(id)) this
    else {
      require(balance >= amount, "Not enough funds")
      copy(pendingTransfer = Some(PendingTransfer.Outgoing(id, amount)))
    }
  }

  def prepareIncomingTransfer(id: Transfer.TransferID, amount: PosAmount): AccountState =
    if (transferHistory.contains(id)) this
    else copy(pendingTransfer = Some(PendingTransfer.Incoming(id, amount)))

  def deposit(amount: PosAmount): AccountState = copy(balance = balance + amount)
  def withdraw(amount: PosAmount): AccountState = copy(balance = balance - amount)
}

object AccountState {
  sealed trait PendingTransfer {
    def id: Transfer.TransferID
    def amount: PosAmount
  }
  object PendingTransfer {
    final case class Incoming(id: Transfer.TransferID, amount: PosAmount) extends PendingTransfer
    final case class Outgoing(id: Transfer.TransferID, amount: PosAmount) extends PendingTransfer
  }
}
