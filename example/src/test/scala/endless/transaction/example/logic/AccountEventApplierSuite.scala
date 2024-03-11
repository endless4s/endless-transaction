package endless.transaction.example.logic

import cats.data.NonEmptyList
import endless.transaction.example.Generators
import endless.transaction.example.data.AccountEvent.Opened
import endless.transaction.example.data.AccountState.{PendingTransfer, PendingTransfers}
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.data.{AccountEvent, AccountState, NonNegAmount, PosAmount}
import org.scalacheck.Prop.forAll
import cats.syntax.eq.*

class AccountEventApplierSuite extends munit.ScalaCheckSuite with Generators {
  test("account opened when empty leads to account with zero balance") {
    val applier = new AccountEventApplier
    applier.apply(None, Opened) == Right(Some(AccountState(NonNegAmount(0))))
  }

  test("account opened when non-empty leads to error") {
    val applier = new AccountEventApplier
    applier.apply(Some(AccountState(NonNegAmount(1))), Opened) == Left("Account already exists")
  }

  test("deposited amount is added to balance") {
    forAll { (amount: PosAmount, state: AccountState) =>
      val applier = new AccountEventApplier
      applier.apply(Some(state), AccountEvent.Deposited(amount)) == Right(
        Some(state.copy(balance = state.balance + amount))
      )
    }
  }

  test("withdrawn amount is subtracted from balance") {
    forAll(for {
      amount <- posAmountGen
      state <- accountStateGen.suchThat(_.balance >= amount)
    } yield (amount, state)) { case (amount, state) =>
      val applier = new AccountEventApplier
      applier.apply(Some(state), AccountEvent.Withdrawn(amount)) == Right(
        Some(state.copy(balance = state.balance - amount))
      )
    }
  }

  test("outgoing transfer prepared") {
    forAll(for {
      id <- transferIDGen
      amount <- posAmountGen
      state <- accountStateGen.suchThat(_.balance >= amount)
    } yield (id, amount, state)) { case (id, amount, state) =>
      val applier = new AccountEventApplier
      applier.apply(Some(state), AccountEvent.OutgoingTransferPrepared(id, amount)) == Right(
        Some(
          state.copy(pendingTransfers =
            Some(AccountState.PendingTransfers.SingleOutgoing(PendingTransfer.Outgoing(id, amount)))
          )
        )
      )
    }
  }

  test("incoming transfer prepared") {
    forAll { (id: TransferID, amount: PosAmount, state: AccountState) =>
      val applier = new AccountEventApplier
      val expectedState =
        if (state.pendingOutgoingTransfer.isDefined) state
        else
          state.copy(pendingTransfers =
            Some(
              AccountState.PendingTransfers.AtLeastOneIncoming(
                NonEmptyList.fromListUnsafe(
                  state.pendingIncomingTransfers :+ AccountState.PendingTransfer
                    .Incoming(id, amount)
                )
              )
            )
          )
      applier.apply(Some(state), AccountEvent.IncomingTransferPrepared(id, amount)) == Right(
        Some(expectedState)
      )

    }
  }

  test("transfer committed event updates balance according to transfer type") {
    forAll(accountStateWithPendingTransferGen) { state =>
      val applier = new AccountEventApplier
      val pendingTransfers = state.pendingTransfers.get
      val pendingTransfer = pendingTransfers match {
        case AccountState.PendingTransfers.AtLeastOneIncoming(transfers) =>
          transfers.head
        case AccountState.PendingTransfers.SingleOutgoing(transfer) =>
          transfer
      }
      val expectedBalance = pendingTransfer match {
        case PendingTransfer.Incoming(_, amount) => (state.balance + amount).value
        case PendingTransfer.Outgoing(_, amount) => (state.balance - amount).value
      }
      applier.apply(Some(state), AccountEvent.TransferCommitted(pendingTransfer.id)) == Right(
        Some(
          state.copy(
            balance = NonNegAmount(expectedBalance),
            pendingTransfers = pendingTransfers match {
              case PendingTransfers.SingleOutgoing(_) => None
              case PendingTransfers.AtLeastOneIncoming(transfers) =>
                NonEmptyList
                  .fromList(transfers.filterNot(_.id === pendingTransfer.id))
                  .map(PendingTransfers.AtLeastOneIncoming)
            },
            transferHistory = Set(pendingTransfer.id)
          )
        )
      )
    }
  }

  test("transfer aborted clears pending transfer") {
    forAll(accountStateWithPendingTransferGen) { state =>
      val applier = new AccountEventApplier
      val pendingTransfer = state.pendingTransfers.get match {
        case AccountState.PendingTransfers.AtLeastOneIncoming(transfers) =>
          transfers.head
        case AccountState.PendingTransfers.SingleOutgoing(transfer) =>
          transfer
      }
      val expectedTransfers = state.pendingTransfers.get match {
        case PendingTransfers.AtLeastOneIncoming(transfers) =>
          NonEmptyList
            .fromList(transfers.filterNot(_.id === pendingTransfer.id))
            .map(PendingTransfers.AtLeastOneIncoming)
        case PendingTransfers.SingleOutgoing(_) => None
      }
      applier.apply(Some(state), AccountEvent.TransferAborted(pendingTransfer.id)) == Right(
        Some(
          state.copy(
            pendingTransfers = expectedTransfers,
            transferHistory = Set(pendingTransfer.id)
          )
        )
      )
    }
  }
}
