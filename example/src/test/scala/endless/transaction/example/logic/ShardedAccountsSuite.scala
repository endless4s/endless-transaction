package endless.transaction.example.logic

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.either.*
import endless.\/
import endless.core.entity.Sharding
import endless.transaction.Transaction.AbortReason
import endless.transaction.{Coordinator, Transaction}
import endless.transaction.example.Generators
import endless.transaction.example.algebra.Account
import endless.transaction.example.algebra.Accounts.TransferFailure
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.data.{AccountID, PosAmount, Transfer}
import endless.transaction.helpers.LogMessageAssertions
import org.scalacheck.effect.PropF.forAllF
import org.typelevel.log4cats.testing.TestingLogger

class ShardedAccountsSuite
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite
    with Generators
    with LogMessageAssertions {
  private implicit val logger: TestingLogger[IO] = TestingLogger.impl[IO]()
  private implicit val sharding: Sharding[IO, AccountID, Account] =
    (_: AccountID) => fail("should not be called")

  test("transfer creates a transaction and successfully commits it") {
    forAllF { (from: AccountID, to: AccountID, amount: PosAmount) =>
      val accounts =
        new ShardedAccounts[IO](sharding, testCoordinator(Transaction.Status.Committed))
      assertIO(accounts.transfer(from, to, amount), Right(()))
    }
  }

  test("transfer creates a transaction which times out") {
    forAllF { (from: AccountID, to: AccountID, amount: PosAmount) =>
      val accounts = new ShardedAccounts[IO](
        sharding,
        testCoordinator(Transaction.Status.Aborted(AbortReason.Timeout))
      )
      assertIO(accounts.transfer(from, to, amount), Left(TransferFailure.Timeout))
    }
  }

  test("transfer creates a transaction which is aborted by the client") {
    forAllF { (from: AccountID, to: AccountID, amount: PosAmount, reason: TransferFailure) =>
      val accounts = new ShardedAccounts[IO](
        sharding,
        testCoordinator(Transaction.Status.Aborted(AbortReason.Client(Some(reason))))
      )
      assertIO(accounts.transfer(from, to, amount), Left(reason))
    }
  }

  test("transfer creates a transaction which is aborted by the client without reason") {
    forAllF { (from: AccountID, to: AccountID, amount: PosAmount) =>
      val accounts = new ShardedAccounts[IO](
        sharding,
        testCoordinator(Transaction.Status.Aborted(AbortReason.Client(None)))
      )

      interceptIO[Exception](accounts.transfer(from, to, amount)) >> IO.unit
    }
  }

  test("transfer creates a transaction which is aborted by a branch") {
    forAllF { (from: AccountID, to: AccountID, amount: PosAmount, reason: TransferFailure) =>
      val accounts = new ShardedAccounts[IO](
        sharding,
        testCoordinator(Transaction.Status.Aborted(AbortReason.Branches(NonEmptyList.of(reason))))
      )
      assertIO(accounts.transfer(from, to, amount), Left(reason))
    }
  }

  test("transfer creates a transaction that fails") {
    forAllF { (from: AccountID, to: AccountID, amount: PosAmount, errors: NonEmptyList[String]) =>
      val accounts =
        new ShardedAccounts[IO](sharding, testCoordinator(Transaction.Status.Failed(errors)))

      interceptIO[Exception](accounts.transfer(from, to, amount)) >> logger.assertLogsError
    }
  }

  def testCoordinator(testStatus: Transaction.Status[TransferFailure]) =
    new Coordinator[IO, TransferID, AccountID, Transfer, TransferFailure] {
      def create(
          id: TransferID,
          query: Transfer,
          branch: AccountID,
          otherBranches: AccountID*
      ): Resource[IO, Transaction[IO, AccountID, Transfer, TransferFailure]] =
        Resource.pure(new Transaction[IO, AccountID, Transfer, TransferFailure] {
          def query: IO[Transaction.Unknown.type \/ Transfer] = fail("should not be called")
          def branches: IO[Transaction.Unknown.type \/ Set[AccountID]] =
            fail("should not be called")
          def status: IO[Transaction.Unknown.type \/ Transaction.Status[TransferFailure]] =
            IO(testStatus.asRight)
          def abort(reason: Option[TransferFailure]): IO[Transaction.AbortError \/ Unit] =
            fail("should not be called")
        })

      def get(id: TransferID): Resource[IO, Transaction[IO, AccountID, Transfer, TransferFailure]] =
        fail("should not be called")
    }
}
