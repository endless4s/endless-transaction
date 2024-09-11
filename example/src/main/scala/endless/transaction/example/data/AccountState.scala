package endless.transaction.example.data
import cats.data.NonEmptyList
import cats.syntax.eq.*
import cats.syntax.option.*
import endless.transaction.example.data.AccountState.{PendingTransfer, PendingTransfers}

final case class AccountState(
    balance: NonNegAmount,
    pendingTransfers: Option[PendingTransfers] = None,
    transferHistory: Set[Transfer.TransferID] = Set.empty
) {
  lazy val pendingOutgoingTransfer: Option[PendingTransfer.Outgoing] = pendingTransfers.collect {
    case PendingTransfers.SingleOutgoing(transfer) => transfer
  }
  lazy val pendingIncomingTransfers: List[PendingTransfer.Incoming] = pendingTransfers
    .collect { case PendingTransfers.AtLeastOneIncoming(transfers) =>
      transfers.toList
    }
    .getOrElse(List.empty)

  def getPendingTransfer(id: Transfer.TransferID): Option[PendingTransfer] =
    pendingTransfers.flatMap {
      case PendingTransfers.SingleOutgoing(transfer) => Option.when(transfer.id === id)(transfer)
      case PendingTransfers.AtLeastOneIncoming(transfers) => transfers.find(_.id === id)
    }

  def abortTransfer(id: Transfer.TransferID): AccountState = {
    if (transferHistory.contains(id)) this
    else {
      require(getPendingTransfer(id).nonEmpty, "Transfer is unknown")
      copy(
        pendingTransfers = pendingTransfers match {
          case Some(PendingTransfers.SingleOutgoing(_)) => None
          case Some(PendingTransfers.AtLeastOneIncoming(transfers)) =>
            NonEmptyList
              .fromList(transfers.filterNot(_.id === id))
              .map(PendingTransfers.AtLeastOneIncoming.apply)
          case None => None
        },
        transferHistory = transferHistory + id
      )
    }
  }

  def commitTransfer(id: Transfer.TransferID): AccountState = {
    if (transferHistory.contains(id)) this
    else {
      require(getPendingTransfer(id).nonEmpty, "Transfer is unknown")
      copy(
        pendingTransfers = pendingTransfers match {
          case Some(PendingTransfers.SingleOutgoing(_)) => None
          case Some(PendingTransfers.AtLeastOneIncoming(transfers)) =>
            NonEmptyList
              .fromList(transfers.filterNot(_.id === id))
              .map(PendingTransfers.AtLeastOneIncoming.apply)
          case None => None
        },
        balance = pendingTransfers match {
          case Some(PendingTransfers.SingleOutgoing(transfer)) => balance - transfer.amount
          case Some(PendingTransfers.AtLeastOneIncoming(transfers)) =>
            transfers.find(_.id === id).map(_.amount).fold(balance)(balance + _)
          case _ => balance
        },
        transferHistory = transferHistory + id
      )
    }
  }

  def prepareOutgoingTransfer(id: Transfer.TransferID, amount: PosAmount): AccountState = {
    if (transferHistory.contains(id)) this
    else {
      require(balance >= amount, "Not enough funds")
      copy(pendingTransfers =
        PendingTransfers.SingleOutgoing(PendingTransfer.Outgoing(id, amount)).some
      )
    }
  }

  def prepareIncomingTransfer(id: Transfer.TransferID, amount: PosAmount): AccountState =
    if (transferHistory.contains(id)) this
    else
      copy(pendingTransfers = pendingTransfers match {
        case Some(PendingTransfers.SingleOutgoing(_)) => pendingTransfers
        case Some(PendingTransfers.AtLeastOneIncoming(transfers)) =>
          PendingTransfers
            .AtLeastOneIncoming(transfers :+ PendingTransfer.Incoming(id, amount))
            .some
        case None =>
          PendingTransfers
            .AtLeastOneIncoming(NonEmptyList.one(PendingTransfer.Incoming(id, amount)))
            .some
      })

  def deposit(amount: PosAmount): AccountState = copy(balance = balance + amount)
  def withdraw(amount: PosAmount): AccountState = copy(balance = balance - amount)
}

object AccountState {
  @SuppressWarnings(Array("org.wartremover.warts.Equals", "org.wartremover.warts.FinalVal"))
  implicit val show: cats.Show[AccountState] = cats.derived.semiauto.showPretty

  sealed trait PendingTransfers
  object PendingTransfers {
    final case class AtLeastOneIncoming(transfers: NonEmptyList[PendingTransfer.Incoming])
        extends PendingTransfers {
      lazy val ids: Set[Transfer.TransferID] = transfers.map(_.id).toList.toSet
    }
    final case class SingleOutgoing(transfer: PendingTransfer.Outgoing) extends PendingTransfers {
      lazy val ids: Set[Transfer.TransferID] = Set(transfer.id)
    }
  }

  sealed trait PendingTransfer {
    def id: Transfer.TransferID
    def amount: PosAmount
  }
  object PendingTransfer {
    final case class Incoming(id: Transfer.TransferID, amount: PosAmount) extends PendingTransfer
    final case class Outgoing(id: Transfer.TransferID, amount: PosAmount) extends PendingTransfer
  }
}
