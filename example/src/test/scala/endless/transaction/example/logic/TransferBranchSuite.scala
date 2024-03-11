package endless.transaction.example.logic

import cats.effect.IO
import cats.effect.kernel.Ref
import endless.\/
import endless.transaction.example.algebra.Account
import endless.transaction.example.data.{NonNegAmount, PosAmount, Transfer, TransferParameters}
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.Generators
import cats.syntax.either.*
import endless.transaction.Branch
import endless.transaction.example.algebra.Accounts.TransferFailure
import endless.transaction.example.helpers.RetryHelpers.RetryParameters
import endless.transaction.helpers.LogMessageAssertions
import org.scalacheck.effect.PropF.forAllF
import org.typelevel.log4cats.testing.TestingLogger

import scala.concurrent.duration.DurationInt

class TransferBranchSuite
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite
    with Generators
    with LogMessageAssertions {
  implicit val testLogger: TestingLogger[IO] = TestingLogger.impl[IO]()
  implicit val retryParameters: TransferParameters.BranchRetryParameters =
    TransferParameters.BranchRetryParameters(
      onError = RetryParameters(10.millis, 3),
      onPendingTransfer = RetryParameters(10.millis, 3)
    )

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
          ): IO[Account.Unknown.type \/ Unit] = IO {
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
          ): IO[Account.Unknown.type \/ Unit] = IO.pure(Account.Unknown.asLeft)
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

  test("prepare retries if there is a pending outgoing transfer") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      for {
        called <- Ref.of[IO, Int](0)
        branch = new TransferBranch[IO](
          transfer.origin,
          new TestAccount {
            override def prepareOutgoingTransfer(
                id: TransferID,
                transfer: Transfer
            ): IO[Account.WithdrawFailure \/ Unit] = for {
              count <- called.getAndUpdate(_ + 1)
              result <-
                if (count < 2) IO(Account.PendingOutgoingTransfer.asLeft)
                else IO(().asRight)
            } yield result
          }
        )
        _ <- assertIO(
          branch.prepare(transferID, transfer),
          Branch.Vote.Commit
        )
        _ <- testLogger.assertLogsWarn
      } yield ()
    }
  }

  test("prepare aborts the transaction if the destination account is unknown") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val branch = new TransferBranch[IO](
        transfer.destination,
        new TestAccount {
          override def prepareIncomingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.Unknown.type \/ Unit] = IO.pure(Account.Unknown.asLeft)
        }
      )
      assertIO(
        branch.prepare(transferID, transfer),
        Branch.Vote.Abort(TransferFailure.AccountNotFound(transfer.destination))
      ) >> testLogger.assertLogsDebug
    }
  }

  test("prepare votes to commit the transaction for the destination branch") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      val branch = new TransferBranch[IO](
        transfer.destination,
        new TestAccount {
          override def prepareIncomingTransfer(
              id: TransferID,
              transfer: Transfer
          ): IO[Account.Unknown.type \/ Unit] = IO.pure(().asRight)
        }
      )
      assertIO(
        branch.prepare(transferID, transfer),
        Branch.Vote.Commit
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

  test("prepare outgoing transfer retries in case of exception") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      for {
        called <- Ref.of[IO, Int](0)
        branch = new TransferBranch[IO](
          transfer.origin,
          new TestAccount {
            override def prepareOutgoingTransfer(
                id: TransferID,
                transfer: Transfer
            ): IO[Account.WithdrawFailure \/ Unit] = for {
              count <- called.getAndUpdate(_ + 1)
              result <-
                if (count < 2) IO.raiseError(new Exception("boom"))
                else IO(().asRight)
            } yield result
          }
        )
        _ <- assertIO(
          branch.prepare(transferID, transfer),
          Branch.Vote.Commit
        )
        _ <- testLogger.assertLogsWarn
      } yield ()
    }
  }

  test("prepare incoming transfer retries in case of exception") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      for {
        called <- Ref.of[IO, Int](0)
        branch = new TransferBranch[IO](
          transfer.destination,
          new TestAccount {
            override def prepareIncomingTransfer(
                id: TransferID,
                transfer: Transfer
            ): IO[Account.Unknown.type \/ Unit] = for {
              count <- called.getAndUpdate(_ + 1)
              result <-
                if (count < 2) IO.raiseError(new Exception("boom"))
                else IO(().asRight)
            } yield result
          }
        )
        _ <- assertIO(
          branch.prepare(transferID, transfer),
          Branch.Vote.Commit
        )
        _ <- testLogger.assertLogsWarn
      } yield ()
    }
  }

  test("commit retries in case of error") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      for {
        called <- Ref.of[IO, Int](0)
        branch = new TransferBranch[IO](
          transfer.destination,
          new TestAccount {
            override def commitTransfer(
                id: TransferID
            ): IO[Account.TransferFailure \/ Unit] = for {
              count <- called.getAndUpdate(_ + 1)
              result <-
                if (count < 2) IO.raiseError(new Exception("boom"))
                else IO(().asRight)
            } yield result
          }
        )
        _ <- assertIO(
          branch.commit(transferID),
          ()
        )
        _ <- testLogger.assertLogsWarn
      } yield ()
    }
  }

  test("abort retries in case of error") {
    forAllF { (transferID: TransferID, transfer: Transfer) =>
      for {
        called <- Ref.of[IO, Int](0)
        branch = new TransferBranch[IO](
          transfer.destination,
          new TestAccount {
            override def abortTransfer(
                id: TransferID
            ): IO[Account.TransferFailure \/ Unit] = for {
              count <- called.getAndUpdate(_ + 1)
              result <-
                if (count < 2) IO.raiseError(new Exception("boom"))
                else IO(().asRight)
            } yield result
          }
        )
        _ <- assertIO(
          branch.abort(transferID),
          ()
        )
        _ <- testLogger.assertLogsWarn
      } yield ()
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
    ): IO[Account.Unknown.type \/ Unit] = fail("should not be called")

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
