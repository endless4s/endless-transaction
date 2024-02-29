package endless.transaction.example.logic

import cats.data.Chain
import cats.effect.IO
import endless.core.interpret.EntityT
import endless.transaction.example.Generators
import endless.transaction.example.algebra.Account.*
import endless.transaction.example.data.{AccountEvent, AccountState}
import org.scalacheck.effect.PropF.forAllF
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger

class AccountEntityBehaviorSuite
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite
    with Generators {
  private implicit val logger: Logger[IO] = TestingLogger.impl[IO]()
  private val behavior = AccountEntityBehavior(EntityT.instance[IO, AccountState, AccountEvent])
  private implicit val eventApplier: AccountEventApplier = new AccountEventApplier

  test("open writes opened event") {
    behavior.open.run(None).map {
      case Right((events, _)) =>
        assertEquals(events, Chain(AccountEvent.Opened))
      case Left(error) => fail(error)
    }
  }

  test("open returns error with existing account") {
    forAllF(emptyAccountStateGen) { state =>
      behavior.open.run(Some(state)).map {
        case Right((_, Right(_)))            => fail("account should not exist")
        case Right((_, Left(AlreadyExists))) => ()
        case Left(error)                     => fail(error)
      }
    }
  }

  test("balance returns error with unknown account") {
    behavior.balance.run(None).map {
      case Right((_, Left(Unknown))) => ()
      case Right((_, Right(_)))      => fail("account should not exist")
      case Left(error)               => fail(error)
    }
  }

  test("balance returns balance with known account") {
    forAllF(accountStateGen) { state =>
      behavior.balance.run(Some(state)).map {
        case Right((_, Left(_)))        => fail("account should exist")
        case Right((_, Right(balance))) => assertEquals(balance, state.balance)
        case Left(error)                => fail(error)
      }
    }
  }

  test("prepareOutgoingTransfer returns error with unknown account") {
    forAllF(transferIDGen, transferGen) { (id, transfer) =>
      behavior.prepareOutgoingTransfer(id, transfer).run(None).map {
        case Right((_, Left(Unknown)))                 => ()
        case Right((_, Left(InsufficientFunds(_))))    => fail("account should not exist")
        case Right((_, Left(PendingOutgoingTransfer))) => fail("account should not exist")
        case Right((_, Right(_)))                      => fail("account should not exist")
        case Left(error)                               => fail(error)
      }
    }
  }

  test("prepareOutgoingTransfer returns error with insufficient funds") {
    forAllF(for {
      state <- accountStateWithoutPendingTransferGen
      transfer <- transferGen.suchThat(_.amount.value > state.balance.value)
      id <- transferIDGen
    } yield (state, transfer, id)) { case (state, transfer, id) =>
      behavior.prepareOutgoingTransfer(id, transfer).run(Some(state)).map {
        case Right((_, Left(InsufficientFunds(_))))    => ()
        case Right((_, Left(PendingOutgoingTransfer))) => fail("wrong error")
        case Right((_, Left(Unknown)))                 => fail("account should exist")
        case Right((_, Right(_)))                      => fail("transfer should fail")
        case Left(error)                               => fail(error)
      }
    }
  }

  test("prepareOutgoingTransfer writes outgoing transfer prepared event with sufficient funds") {
    forAllF(for {
      state <- accountStateWithoutPendingTransferGen
      transfer <- transferGen.suchThat(_.amount.value <= state.balance.value)
      id <- transferIDGen
    } yield (state, transfer, id)) { case (state, transfer, id) =>
      behavior.prepareOutgoingTransfer(id, transfer).run(Some(state)).map {
        case Right((events, Right(_))) =>
          assertEquals(events, Chain(AccountEvent.OutgoingTransferPrepared(id, transfer.amount)))
        case Right((_, Left(_))) => fail("should not fail")
        case Left(error)         => fail(error)
      }
    }
  }

  test("prepareOutgoingTransfer returns error with pending transfer") {
    forAllF(accountStateWithPendingTransferGen, transferIDGen, transferGen) {
      (state, id, transfer) =>
        behavior.prepareOutgoingTransfer(id, transfer).run(Some(state)).map {
          case Right((_, Left(PendingOutgoingTransfer))) => ()
          case Right((_, Left(InsufficientFunds(_))))    => fail("wrong error")
          case Right((_, Left(Unknown)))                 => fail("account should exist")
          case Right((_, Right(_)))                      => fail("transfer should fail")
          case Left(error)                               => fail(error)
        }
    }
  }

  test("prepareOutgoingTransfer ignores already prepared transfer") {
    forAllF(accountStateWithoutPendingTransferGen, transferGen, transferIDGen) {
      (state, transfer, id) =>
        behavior
          .prepareOutgoingTransfer(id, transfer)
          .run(Some(state.copy(transferHistory = state.transferHistory + id)))
          .map {
            case Right((_, Right(_))) => ()
            case Right((_, Left(_)))  => fail("account should exist")
            case Left(error)          => fail(error)
          }
    }
  }

  test("prepareIncomingTransfer returns error with unknown account") {
    forAllF(transferIDGen, transferGen) { (id, transfer) =>
      behavior.prepareIncomingTransfer(id, transfer).run(None).map {
        case Right((_, Left(Unknown)))                 => ()
        case Right((_, Left(PendingIncomingTransfer))) => fail("account should not exist")
        case Right((_, Right(_)))                      => fail("account should not exist")
        case Left(error)                               => fail(error)
      }
    }
  }

  test("prepareIncomingTransfer writes incoming transfer prepared event") {
    forAllF(accountStateWithoutPendingTransferGen, transferGen, transferIDGen) {
      (state, transfer, id) =>
        behavior.prepareIncomingTransfer(id, transfer).run(Some(state)).map {
          case Right((events, Right(_))) =>
            assertEquals(events, Chain(AccountEvent.IncomingTransferPrepared(id, transfer.amount)))
          case Right((_, Left(_))) => fail("account should exist")
          case Left(error)         => fail(error)
        }
    }
  }

  test("prepareIncomingTransfer ignores already prepared transfer") {
    forAllF(accountStateWithoutPendingTransferGen, transferGen, transferIDGen) {
      (state, transfer, id) =>
        behavior
          .prepareIncomingTransfer(id, transfer)
          .run(Some(state.copy(transferHistory = state.transferHistory + id)))
          .map {
            case Right((_, Right(_))) => ()
            case Right((_, Left(_)))  => fail("account should exist")
            case Left(error)          => fail(error)
          }
    }
  }

  test("prepareIncomingTransfer returns error with pending transfer") {
    forAllF(accountStateWithPendingTransferGen, transferIDGen, transferGen) {
      (state, id, transfer) =>
        behavior.prepareIncomingTransfer(id, transfer).run(Some(state)).map {
          case Right((_, Left(PendingIncomingTransfer))) => ()
          case Right((_, Left(Unknown)))                 => fail("account should exist")
          case Right((_, Right(_)))                      => fail("transfer should fail")
          case Left(error)                               => fail(error)
        }
    }
  }

  test("commitTransfer returns error with unknown account") {
    forAllF(transferIDGen) { id =>
      behavior.commitTransfer(id).run(None).map {
        case Right((_, Left(Unknown)))            => ()
        case Right((_, Left(TransferUnknown(_)))) => fail("account should not exist")
        case Right((_, Right(_)))                 => fail("account should not exist")
        case Left(error)                          => fail(error)
      }
    }
  }

  test("commitTransfer returns error with unknown transfer") {
    forAllF(accountStateGen, transferIDGen) { (state, id) =>
      behavior.commitTransfer(id).run(Some(state)).map {
        case Right((_, Left(TransferUnknown(_)))) => ()
        case Right((_, Left(_)))                  => fail("account should exist")
        case Right((_, Right(_)))                 => fail("transfer should fail")
        case Left(error)                          => fail(error)
      }
    }
  }

  test("commitTransfer writes transfer committed event") {
    forAllF(accountStateWithPendingTransferGen) { state =>
      behavior
        .commitTransfer(state.pendingTransfer.get.id)
        .run(Some(state))
        .map {
          case Right((events, Right(_))) =>
            assertEquals(
              events,
              Chain(AccountEvent.TransferCommitted(state.pendingTransfer.get.id))
            )
          case Right((_, Left(_))) => fail("account should exist")
          case Left(error)         => fail(error)
        }
    }
  }

  test("commitTransfer ignores already committed transfer") {
    forAllF(accountStateWithPendingTransferGen) { state =>
      behavior
        .commitTransfer(state.pendingTransfer.get.id)
        .run(
          Some(state.copy(transferHistory = state.transferHistory + state.pendingTransfer.get.id))
        )
        .map {
          case Right((_, Right(_))) => ()
          case Right((_, Left(_)))  => fail("account should exist")
          case Left(error)          => fail(error)
        }
    }
  }

  test("abortTransfer returns error with unknown account") {
    forAllF(transferIDGen) { id =>
      behavior.abortTransfer(id).run(None).map {
        case Right((_, Left(Unknown)))            => ()
        case Right((_, Left(TransferUnknown(_)))) => fail("account should not exist")
        case Right((_, Right(_)))                 => fail("account should not exist")
        case Left(error)                          => fail(error)
      }
    }
  }

  test("abortTransfer returns error with unknown transfer") {
    forAllF(accountStateGen, transferIDGen) { (state, id) =>
      behavior.abortTransfer(id).run(Some(state)).map {
        case Right((_, Left(TransferUnknown(_)))) => ()
        case Right((_, Left(_)))                  => fail("account should exist")
        case Right((_, Right(_)))                 => fail("transfer should fail")
        case Left(error)                          => fail(error)
      }
    }
  }

  test("abortTransfer writes transfer aborted event") {
    forAllF(accountStateWithPendingTransferGen) { state =>
      behavior
        .abortTransfer(state.pendingTransfer.get.id)
        .run(Some(state))
        .map {
          case Right((events, Right(_))) =>
            assertEquals(
              events,
              Chain(AccountEvent.TransferAborted(state.pendingTransfer.get.id))
            )
          case Right((_, Left(_))) => fail("account should exist")
          case Left(error)         => fail(error)
        }
    }
  }

  test("abortTransfer ignores already aborted transfer") {
    forAllF(accountStateWithPendingTransferGen) { state =>
      behavior
        .abortTransfer(state.pendingTransfer.get.id)
        .run(
          Some(state.copy(transferHistory = state.transferHistory + state.pendingTransfer.get.id))
        )
        .map {
          case Right((_, Right(_))) => ()
          case Right((_, Left(_)))  => fail("account should exist")
          case Left(error)          => fail(error)
        }
    }
  }

  test("withdraw returns error with unknown account") {
    forAllF(posAmountGen) { amount =>
      behavior.withdraw(amount).run(None).map {
        case Right((_, Left(Unknown)))                 => ()
        case Right((_, Left(PendingOutgoingTransfer))) => fail("wrong error")
        case Right((_, Left(InsufficientFunds(_))))    => fail("account should not exist")
        case Right((_, Right(_)))                      => fail("account should not exist")
        case Left(error)                               => fail(error)
      }
    }
  }

  test("withdraw returns error with insufficient funds") {
    forAllF(for {
      state <- accountStateWithoutPendingTransferGen
      amount <- posAmountGen.suchThat(_.value > state.balance.value)
    } yield (state, amount)) { case (state, amount) =>
      behavior.withdraw(amount).run(Some(state)).map {
        case Right((_, Left(InsufficientFunds(_)))) => ()
        case Right((_, Left(PendingOutgoingTransfer))) =>
          fail("account should not have pending transfer")
        case Right((_, Left(Unknown))) => fail("account should exist")
        case Right((_, Right(_)))      => fail("withdraw should fail")
        case Left(error)               => fail(error)
      }
    }
  }

  test("withdraw returns error with pending transfer") {
    forAllF(accountStateWithPendingTransferGen, posAmountGen) { (state, amount) =>
      behavior.withdraw(amount).run(Some(state)).map {
        case Right((_, Left(PendingOutgoingTransfer))) => ()
        case Right((_, Left(InsufficientFunds(_))))    => fail("wrong error")
        case Right((_, Left(Unknown)))                 => fail("account should exist")
        case Right((_, Right(_)))                      => fail("withdraw should fail")
        case Left(error)                               => fail(error)
      }
    }
  }

  test("withdraw writes withdrawn event with sufficient funds") {
    forAllF(for {
      state <- accountStateWithoutPendingTransferGen
      amount <- posAmountGen.suchThat(_.value <= state.balance.value)
    } yield (state, amount)) { case (state, amount) =>
      behavior.withdraw(amount).run(Some(state)).map {
        case Right((events, Right(_))) =>
          assertEquals(events, Chain(AccountEvent.Withdrawn(amount)))
        case Right((_, Left(_))) => fail("account should exist")
        case Left(error)         => fail(error)
      }
    }
  }

  test("deposit returns error with unknown account") {
    forAllF(posAmountGen) { amount =>
      behavior.deposit(amount).run(None).map {
        case Right((_, Left(Unknown))) => ()
        case Right((_, Right(_)))      => fail("account should not exist")
        case Left(error)               => fail(error)
      }
    }
  }

  test("deposit writes deposited event") {
    forAllF(accountStateGen, posAmountGen) { (state, amount) =>
      behavior.deposit(amount).run(Some(state)).map {
        case Right((events, Right(_))) =>
          assertEquals(events, Chain(AccountEvent.Deposited(amount)))
        case Right((_, Left(_))) => fail("account should exist")
        case Left(error)         => fail(error)
      }
    }
  }

}
