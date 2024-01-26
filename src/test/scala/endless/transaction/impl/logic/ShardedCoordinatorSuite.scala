package endless.transaction.impl.logic

import cats.data.NonEmptyList
import cats.effect.IO
import endless.\/
import endless.core.entity.Sharding
import endless.transaction.helpers.LogMessageAssertions
import endless.transaction.impl.Generators
import endless.transaction.impl.algebra.{TransactionAlg, TransactionCreator}
import endless.transaction.{Branch, Coordinator, Transaction}
import org.scalacheck.effect.PropF.forAllF
import org.typelevel.log4cats.testing.TestingLogger

class ShardedCoordinatorSuite
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite
    with Generators
    with LogMessageAssertions {
  private implicit val logger: TestingLogger[IO] = TestingLogger.impl[IO]()
  private val shouldNotBeCalled = IO.raiseError(new Exception("Should not be called"))

  test("transaction resource release just logs when in final status") {
    forAllF { (tid: TID, finalTransactionStatus: Transaction.Status.Final[R]) =>
      val testTransaction = new TestTransaction {
        def status: IO[Transaction.Unknown.type \/ Transaction.Status[R]] =
          IO.pure(Right(finalTransactionStatus))
      }
      val sharding = new Sharding[IO, TID, TransactionAlg[_[_], TID, BID, Q, R]] {
        def entityFor(id: TID): TransactionAlg[IO, TID, BID, Q, R] = testTransaction
      }
      val coordinator = new ShardedCoordinator(sharding)
      coordinator.get(tid).use_ >> logger.assertLogsDebug
    }
  }

  test("transaction resource release aborts when in pending status") {
    forAllF { (tid: TID, pendingTransactionStatus: Transaction.Status.Pending[R]) =>
      val testTransaction = new TestTransaction {
        def status: IO[Transaction.Unknown.type \/ Transaction.Status[R]] =
          IO.pure(Right(pendingTransactionStatus))
        override def abort(reason: Option[R]): IO[Transaction.AbortError \/ Unit] =
          IO.pure(Right(()))
      }
      val sharding = new Sharding[IO, TID, TransactionAlg[_[_], TID, BID, Q, R]] {
        def entityFor(id: TID): TransactionAlg[IO, TID, BID, Q, R] = testTransaction
      }
      val coordinator = new ShardedCoordinator(sharding)
      coordinator.get(tid).use_ >> logger.assertLogsDebug
    }
  }

  test("transaction resource release logs a warning when abort fails") {
    forAllF { (tid: TID, pendingTransactionStatus: Transaction.Status.Pending[R]) =>
      val testTransaction = new TestTransaction {
        def status: IO[Transaction.Unknown.type \/ Transaction.Status[R]] =
          IO.pure(Right(pendingTransactionStatus))
        override def abort(reason: Option[R]): IO[Transaction.AbortError \/ Unit] =
          IO.pure(Left(Transaction.TooLateToAbort))
      }
      val sharding = new Sharding[IO, TID, TransactionAlg[_[_], TID, BID, Q, R]] {
        def entityFor(id: TID): TransactionAlg[IO, TID, BID, Q, R] = testTransaction
      }
      val coordinator = new ShardedCoordinator(sharding)
      coordinator.get(tid).use_ >> logger.assertLogsDebug >> logger.assertLogsWarn
    }
  }

  test("transaction resource release just logs debug when transaction is not yet created") {
    forAllF { (tid: TID) =>
      val testTransaction = new TestTransaction {
        def status: IO[Transaction.Unknown.type \/ Transaction.Status[R]] =
          IO.pure(Left(Transaction.Unknown))
      }
      val sharding = new Sharding[IO, TID, TransactionAlg[_[_], TID, BID, Q, R]] {
        def entityFor(id: TID): TransactionAlg[IO, TID, BID, Q, R] = testTransaction
      }
      val coordinator = new ShardedCoordinator(sharding)
      coordinator.get(tid).use_ >> logger.assertLogsDebug
    }
  }

  test("transaction resource release logs a warning when retrieving the status throws") {
    forAllF { (tid: TID) =>
      val testTransaction = new TestTransaction {
        def status: IO[Transaction.Unknown.type \/ Transaction.Status[R]] =
          IO.raiseError(new Exception("Failed to retrieve status"))
      }
      val sharding = new Sharding[IO, TID, TransactionAlg[_[_], TID, BID, Q, R]] {
        def entityFor(id: TID): TransactionAlg[IO, TID, BID, Q, R] = testTransaction
      }
      val coordinator = new ShardedCoordinator(sharding)
      coordinator.get(tid).use_ >> logger.assertLogsDebug >> logger.assertLogsWarn
    }
  }

  test("create returns a transaction resource when the transaction does not exist") {
    forAllF { (tid: TID, query: Q, branches: NonEmptyList[BID]) =>
      val testTransaction = new TestTransaction {
        override def create(
            id: TID,
            query: Q,
            branches: NonEmptyList[BID]
        ): IO[TransactionCreator.AlreadyExists.type \/ Unit] = IO.pure(Right(()))

        def status: IO[Transaction.Unknown.type \/ Transaction.Status[R]] =
          IO(Right(Transaction.Status.Preparing))
      }
      val sharding = new Sharding[IO, TID, TransactionAlg[_[_], TID, BID, Q, R]] {
        def entityFor(id: TID): TransactionAlg[IO, TID, BID, Q, R] = testTransaction
      }
      val coordinator = new ShardedCoordinator(sharding)
      coordinator
        .create(tid, query, branches.head, branches.head, branches.tail*)
        .use_ >> logger.assertLogsDebug
    }
  }

  test("create raises an error when the transaction already exists") {
    forAllF { (tid: TID, query: Q, branches: NonEmptyList[BID]) =>
      val testTransaction = new TestTransaction {
        override def create(
            id: TID,
            query: Q,
            branches: NonEmptyList[BID]
        ): IO[TransactionCreator.AlreadyExists.type \/ Unit] =
          IO.pure(Left(TransactionCreator.AlreadyExists))

        def status: IO[Transaction.Unknown.type \/ Transaction.Status[R]] =
          IO(Right(Transaction.Status.Preparing))
      }
      val sharding = new Sharding[IO, TID, TransactionAlg[_[_], TID, BID, Q, R]] {
        def entityFor(id: TID): TransactionAlg[IO, TID, BID, Q, R] = testTransaction
      }
      val coordinator = new ShardedCoordinator(sharding)
      interceptIO[Coordinator.TransactionAlreadyExists.type](
        coordinator
          .create(tid, query, branches.head, branches.head, branches.tail*)
          .use_
      ) >> logger.assertLogsDebug >> logger.assertLogsWarn
    }
  }

  private abstract class TestTransaction extends TransactionAlg[IO, TID, BID, Q, R] {

    def branchVoted(branch: BID, vote: Branch.Vote[R]): IO[Unit] = shouldNotBeCalled

    def branchCommitted(branch: BID): IO[Unit] = shouldNotBeCalled

    def branchAborted(branch: BID): IO[Unit] = shouldNotBeCalled

    def branchFailed(branch: BID, error: TID): IO[Unit] = shouldNotBeCalled

    def timeout(): IO[Unit] = shouldNotBeCalled

    def query: IO[Transaction.Unknown.type \/ Q] = shouldNotBeCalled

    def branches: IO[Transaction.Unknown.type \/ Set[BID]] = shouldNotBeCalled

    def abort(reason: Option[R]): IO[Transaction.AbortError \/ Unit] = shouldNotBeCalled

    def create(
        id: TID,
        query: Q,
        branches: NonEmptyList[BID]
    ): IO[TransactionCreator.AlreadyExists.type \/ Unit] = shouldNotBeCalled
  }
}
