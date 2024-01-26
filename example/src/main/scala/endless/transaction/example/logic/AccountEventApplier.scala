package endless.transaction.example.logic

import endless.\/
import endless.core.event.EventApplier
import endless.transaction.example.data.{AccountEvent, AccountState, NonNegAmount}
import cats.syntax.either.*
import AccountEvent.*

class AccountEventApplier extends EventApplier[AccountState, AccountEvent] {

  def apply(maybeState: Option[AccountState], event: AccountEvent): String \/ Option[AccountState] =
    (event match {
      case Opened =>
        maybeState.toLeft(AccountState(NonNegAmount(0))).leftMap(_ => "Account already exists")
      case Deposited(amount) => maybeState.map(_.deposit(amount)).toRight("Account is unknown")
      case Withdrawn(amount) => maybeState.map(_.withdraw(amount)).toRight("Account is unknown")
      case OutgoingTransferPrepared(id, amount) =>
        maybeState.map(_.prepareOutgoingTransfer(id, amount)).toRight("Account is unknown")
      case IncomingTransferPrepared(id, amount) =>
        maybeState.map(_.prepareIncomingTransfer(id, amount)).toRight("Account is unknown")
      case TransferCommitted(id) =>
        maybeState.map(_.commitTransfer(id)).toRight("Account is unknown")
      case TransferAborted(id) => maybeState.map(_.abortTransfer(id)).toRight("Account is unknown")
    }).map(Option(_))

}
