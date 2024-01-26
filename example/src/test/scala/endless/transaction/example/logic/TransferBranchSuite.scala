package endless.transaction.example.logic

import cats.effect.IO
import endless.\/
import endless.transaction.example.algebra.Account
import endless.transaction.example.data.{NonNegAmount, PosAmount, Transfer}
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.Generators
import cats.syntax.either.*
import endless.transaction.Branch
import endless.transaction.example.algebra.Accounts.TransferFailure
import endless.transaction.helpers.LogMessageAssertions
import org.scalacheck.effect.PropF.forAllF
import org.typelevel.log4cats.testing.TestingLogger

class TransferBranchSuite
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite
    with Generators
    with LogMessageAssertions {
  implicit val testLogger: TestingLogger[IO] = TestingLogger.impl[IO]()

  test("preparing the origin branch prepares the outgoing transfer") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val branch = new TransferBranch[IO](
        transfer.origin,
        new TestAccount {
          override def prepareOutgoingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.WithdrawFailure \/ Unit] = IO {
            assertEquals(id, transferID)
            assertEquals(transfer, transfer)
            ().asRight
          }
        }
      )
      assertIO(
        branch.prepare(transferID, transfer),
        Branch.Vote.Commit
      ) >> testLogger.assertLogsDebug
    }
  }

  test("preparing the destination branch prepares the incoming transfer") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val branch = new TransferBranch[IO](
        transfer.destination,
        new TestAccount {
          override def prepareIncomingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.IncomingTransferFailure \/ Unit] = IO {
            assertEquals(id, transferID)
            assertEquals(transfer, transfer)
            ().asRight
          }
        }
      )
      assertIO(
        branch.prepare(transferID, transfer),
        Branch.Vote.Commit
      ) >> testLogger.assertLogsDebug
    }
  }

  test(
    "prepare aborts the transaction if either the origin or the destination accounts are unknown"
  ) {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val destinationBranch = new TransferBranch[IO](
        transfer.destination,
        new TestAccount {
          override def prepareIncomingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.IncomingTransferFailure \/ Unit] = IO.pure(Account.Unknown.asLeft)
        }
      )
      val originBranch = new TransferBranch[IO](
        transfer.origin,
        new TestAccount {
          override def prepareOutgoingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.WithdrawFailure \/ Unit] = IO.pure(Account.Unknown.asLeft)
        }
      )
      assertIO(
        destinationBranch.prepare(transferID, transfer),
        Branch.Vote.Abort(TransferFailure.AccountNotFound(transfer.destination))
      ) >> assertIO(
        originBranch.prepare(transferID, transfer),
        Branch.Vote.Abort(TransferFailure.AccountNotFound(transfer.origin))
      ) >> testLogger.assertLogsDebug
    }
  }

  test("prepare aborts the transaction if there are not enough funds in the origin account") {
    forAllF { (transferID: TransferID, transfer: Transfer, missing: PosAmount) =>
      val branch = new TransferBranch[IO](
        transfer.origin,
        new TestAccount {
          override def prepareOutgoingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.WithdrawFailure \/ Unit] = IO.pure(
            Account.InsufficientFunds(missing).asLeft
          )
        }
      )
      assertIO(
        branch.prepare(transferID, transfer),
        Branch.Vote.Abort(TransferFailure.InsufficientFunds(missing))
      ) >> testLogger.assertLogsDebug
    }
  }

  test("prepare aborts the transaction if there is a pending outgoing transfer") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val branch = new TransferBranch[IO](
        transfer.origin,
        new TestAccount {
          override def prepareOutgoingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.WithdrawFailure \/ Unit] = IO.pure(
            Account.PendingOutgoingTransfer.asLeft
          )
        }
      )
      assertIO(
        branch.prepare(transferID, transfer),
        Branch.Vote.Abort(TransferFailure.OtherPendingTransfer)
      ) >> testLogger.assertLogsDebug
    }
  }

  test("prepare aborts the transaction if there is a pending incoming transfer") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val branch = new TransferBranch[IO](
        transfer.destination,
        new TestAccount {
          override def prepareIncomingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.IncomingTransferFailure \/ Unit] = IO.pure(
            Account.PendingIncomingTransfer.asLeft
          )
        }
      )
      assertIO(
        branch.prepare(transferID, transfer),
        Branch.Vote.Abort(TransferFailure.OtherPendingTransfer)
      ) >> testLogger.assertLogsDebug
    }
  }

  test("commit commits the transaction for both origin and destination branches") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val destinationBranch = new TransferBranch[IO](
        transfer.destination,
        new TestAccount {
          override def commitTransfer(
              id: TransferID
          ): IO[Account.TransferFailure \/ Unit] = IO {
            assertEquals(id, transferID)
            ().asRight
          }
        }
      )
      val originBranch = new TransferBranch[IO](
        transfer.origin,
        new TestAccount {
          override def commitTransfer(
              id: TransferID
          ): IO[Account.TransferFailure \/ Unit] = IO {
            assertEquals(id, transferID)
            ().asRight
          }
        }
      )
      assertIO(destinationBranch.commit(transferID), ()) >> assertIO(
        originBranch.commit(transferID),
        ()
      ) >> testLogger.assertLogsDebug
    }
  }

  test("abort aborts the transaction for both origin and destination branches") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val destinationBranch = new TransferBranch[IO](
        transfer.destination,
        new TestAccount {
          override def abortTransfer(
              id: TransferID
          ): IO[Account.TransferFailure \/ Unit] = IO {
            assertEquals(id, transferID)
            ().asRight
          }
        }
      )
      val originBranch = new TransferBranch[IO](
        transfer.origin,
        new TestAccount {
          override def abortTransfer(
              id: TransferID
          ): IO[Account.TransferFailure \/ Unit] = IO {
            assertEquals(id, transferID)
            ().asRight
          }
        }
      )
      assertIO(destinationBranch.abort(transferID), ()) >> assertIO(
        originBranch.abort(transferID),
        ()
      ) >> testLogger.assertLogsDebug
    }
  }

  private class TestAccount extends Account[IO] {

    def open: IO[Account.AlreadyExists.type \/ Unit] = fail("should not be called")

    def balance: IO[Account.Unknown.type \/ NonNegAmount] = fail("should not be called")

    def prepareOutgoingTransfer(
        id: TransferID,
        transfer: Transfer
    ): IO[Account.WithdrawFailure \/ Unit] = fail("should not be called")

    def prepareIncomingTransfer(
        id: TransferID,
        transfer: Transfer
    ): IO[Account.IncomingTransferFailure \/ Unit] = fail("should not be called")

    def commitTransfer(id: TransferID): IO[Account.TransferFailure \/ Unit] = fail(
      "should not be called"
    )

    def abortTransfer(id: TransferID): IO[Account.TransferFailure \/ Unit] = fail(
      "should not be called"
    )

    def deposit(amount: PosAmount): IO[Account.Unknown.type \/ PosAmount] = fail(
      "should not be called"
    )

    def withdraw(amount: PosAmount): IO[Account.WithdrawFailure \/ NonNegAmount] = fail(
      "should not be called"
    )
  }
}
