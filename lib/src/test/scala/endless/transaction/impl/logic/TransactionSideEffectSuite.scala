package endless.transaction.impl.logic

import cats.data.NonEmptyList
import cats.effect.IO
import endless.\/
import endless.core.entity.Effector
import endless.core.entity.Effector.PassivationState
import endless.core.entity.SideEffect.Trigger
import endless.transaction.{Branch, Transaction}
import endless.transaction.Branch.Vote
import endless.transaction.Transaction.{AbortReason, Status}
import endless.transaction.impl.Generators
import endless.transaction.impl.algebra.{TransactionAlg, TransactionCreator}
import endless.transaction.impl.data.TransactionState
import endless.transaction.impl.helpers.RetryHelpers.RetryParameters
import org.scalacheck.effect.PropF.forAllF
import org.typelevel.log4cats.testing.TestingLogger

import scala.concurrent.duration.DurationInt

class TransactionSideEffectSuite
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite
    with Generators {
  private lazy val shouldNotBeCalled = IO(fail("should not be called"))
  private val neverTimeout = new TimeoutSideEffect[IO] {
    override def scheduleTimeoutAccordingTo[R](status: Status[R]): IO[Unit] = IO.unit
  }
  implicit val testLogger: TestingLogger[IO] = TestingLogger.impl[IO]()
  implicit val retryParameters: RetryParameters = RetryParameters(10.millis, 2)

  test("schedules timeout according to state (after persistence or recovery)") {

    forAllF(transactionStateGen, persistenceOrRecoveryTriggerGen) { (state, trigger) =>
      for {
        scheduleCalled <- IO.ref(false)
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            new TimeoutSideEffect[IO] {
              override def scheduleTimeoutAccordingTo[R](status: Status[R]): IO[Unit] =
                scheduleCalled.set(true)
            },
            (_: BID) =>
              new TestBranch {
                override def prepare(transactionID: TID, query: Q): IO[Vote[R]] = IO(Vote.Commit)
                override def commit(transactionId: TID): IO[Unit] = IO.unit
                override def abort(transactionID: TID, reason: AbortReason[R]): IO[Unit] = IO.unit
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchVoted(branch: BID, vote: Vote[R]): IO[Unit] = IO.unit
              override def branchCommitted(branch: BID): IO[Unit] = IO.unit
              override def branchAborted(branch: BID): IO[Unit] = IO.unit
            },
            Some(state)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(scheduleCalled.get)
      } yield ()
    }
  }

  test("disable passivation in pending state") {
    forAllF(pendingTransactionStateGen, allTriggersGen) { (pending, trigger) =>
      for {
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def prepare(transactionID: TID, query: Q): IO[Vote[R]] = IO(Vote.Commit)
                override def commit(transactionId: TID): IO[Unit] = IO.unit
                override def abort(transactionID: TID, reason: AbortReason[R]): IO[Unit] = IO.unit
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchVoted(branch: BID, vote: Vote[R]): IO[Unit] = IO.unit
              override def branchCommitted(branch: BID): IO[Unit] = IO.unit
              override def branchAborted(branch: BID): IO[Unit] = IO.unit
            },
            Some(pending)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(effector.passivationState.map {
          case PassivationState.Disabled => true; case _ => false
        })
      } yield ()
    }
  }

  test("enabled passivation in final state") {
    forAllF(finalTransactionStateGen, allTriggersGen) { (finalState, trigger) =>
      for {
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def prepare(transactionID: TID, query: Q): IO[Vote[R]] = IO(Vote.Commit)
                override def commit(transactionId: TID): IO[Unit] = IO.unit
                override def abort(transactionID: TID, reason: AbortReason[R]): IO[Unit] = IO.unit
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchVoted(branch: BID, vote: Vote[R]): IO[Unit] = IO.unit
              override def branchCommitted(branch: BID): IO[Unit] = IO.unit
              override def branchAborted(branch: BID): IO[Unit] = IO.unit
            },
            Some(finalState)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(effector.passivationState.map {
          case PassivationState.After(_) => true; case _ => false
        })
      } yield ()
    }
  }

  test(
    "prepares all branches when there are no votes registered yet (after persistence or recovery)"
  ) {
    forAllF(preparingGen, persistenceOrRecoveryTriggerGen) { (preparing, trigger: Trigger) =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](preparing)
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def prepare(transactionID: TID, query: Q): IO[Vote[R]] =
                  IO(Vote.Commit)
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchVoted(branch: BID, vote: Vote[R]): IO[Unit] =
                stateRef.update(_.branchVoted(branch, vote).fold(fail(_), identity))
            },
            Some(preparing)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Committing))
      } yield ()
    }
  }

  test("does not repeat branch preparation right after persisting the first vote") {
    forAllF(preparingGen) { preparing =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](
          preparing.branchVoted(preparing.branches.head, Vote.Commit).fold(fail(_), identity)
        )
        preparingWithHeadBranchVote <- stateRef.get
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def prepare(transactionID: TID, query: Q): IO[Vote[R]] = shouldNotBeCalled
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {},
            Some(preparingWithHeadBranchVote)
          )
        _ <- sideEffect.apply(Trigger.AfterPersistence, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Preparing))
      } yield ()
    }
  }

  test("prepares branches (only with missing votes) after recovery") {
    forAllF(preparingGen) { preparing =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](
          preparing.branchVoted(preparing.branches.head, Vote.Commit).fold(fail(_), identity)
        )
        preparingWithHeadBranchVote <- stateRef.get
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (bid: BID) =>
              new TestBranch {
                override def prepare(transactionID: TID, query: Q): IO[Vote[R]] =
                  if (bid == preparing.branches.head) shouldNotBeCalled else IO(Vote.Commit)
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchVoted(branch: BID, vote: Vote[R]): IO[Unit] =
                stateRef.update(_.branchVoted(branch, vote).fold(fail(_), identity))
            },
            Some(preparingWithHeadBranchVote)
          )
        _ <- sideEffect.apply(Trigger.AfterRecovery, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Committing))
      } yield ()
    }
  }

  test("branch prepare failure (after persistence or recovery)") {
    forAllF(preparingGen, persistenceOrRecoveryTriggerGen) { (preparing, trigger: Trigger) =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](preparing)
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def prepare(transactionID: TID, query: Q): IO[Vote[R]] =
                  IO.raiseError(new Exception("boom"))
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchFailed(branch: BID, error: TID): IO[Unit] =
                stateRef.update(_.branchFailed(branch, error).fold(fail(_), identity))
            },
            Some(preparing)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status match {
          case Status.Failed(_) => true
          case _                => false
        }))
      } yield ()
    }
  }

  test("commit all branches (after persistence or recovery)") {
    forAllF(committingGen, persistenceOrRecoveryTriggerGen) { (committing, trigger: Trigger) =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](committing)
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def commit(transactionId: TID): IO[Unit] =
                  IO.unit
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchCommitted(branch: BID): IO[Unit] =
                stateRef.update(_.branchCommitted(branch).fold(fail(_), identity))
            },
            Some(committing)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Committed))
      } yield ()
    }
  }

  test("commit branches (that still miss commits) after recovery") {
    forAllF(committingGen) { committing =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](
          committing.branchCommitted(committing.branches.head).fold(fail(_), identity)
        )
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (bid: BID) =>
              new TestBranch {
                override def commit(transactionId: TID): IO[Unit] =
                  if (bid == committing.branches.head) shouldNotBeCalled else IO.unit
              }
          )
        )
        committingWithHeadBranchCommitted <- stateRef.get
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchCommitted(branch: BID): IO[Unit] =
                stateRef.update(_.branchCommitted(branch).fold(fail(_), identity))
            },
            Some(committingWithHeadBranchCommitted)
          )
        _ <- sideEffect.apply(Trigger.AfterRecovery, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Committed))
      } yield ()
    }
  }

  test("does not repeat branch commit right after persisting the first commit confirmation") {
    forAllF(committingGen) { committing =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](
          committing.branchCommitted(committing.branches.head).fold(fail(_), identity)
        )
        committingWithHeadBranchCommitted <- stateRef.get
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def commit(transactionId: TID): IO[Unit] = shouldNotBeCalled
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {},
            Some(committingWithHeadBranchCommitted)
          )
        _ <- sideEffect.apply(Trigger.AfterPersistence, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Committing))
      } yield ()
    }
  }

  test("branch commit failure (after persistence or recovery)") {
    forAllF(committingGen, persistenceOrRecoveryTriggerGen) { (committing, trigger: Trigger) =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](committing)
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def commit(transactionId: TID): IO[Unit] =
                  IO.raiseError(new Exception("boom"))
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchFailed(branch: BID, error: TID): IO[Unit] =
                stateRef.update(_.branchFailed(branch, error).fold(fail(_), identity))
            },
            Some(committing)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status match {
          case Status.Failed(_) => true
          case _                => false
        }))
      } yield ()
    }
  }

  test("abort all branches (after persistence or recovery)") {
    forAllF(abortingGen, persistenceOrRecoveryTriggerGen) { (aborting, trigger: Trigger) =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](aborting)
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def abort(transactionID: TID, reason: AbortReason[R]): IO[Unit] =
                  assertIOBoolean(IO(reason == aborting.reason))
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchAborted(branch: BID): IO[Unit] =
                stateRef.update(_.branchAborted(branch).fold(fail(_), identity))
            },
            Some(aborting)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Aborted(aborting.reason)))
      } yield ()
    }
  }

  test("abort branches (only with missing aborts) after recovery") {
    forAllF(abortingGen) { aborting =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](
          aborting.branchAborted(aborting.branches.head).fold(fail(_), identity)
        )
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (bid: BID) =>
              new TestBranch {
                override def abort(transactionID: TID, reason: AbortReason[R]): IO[Unit] =
                  if (bid == aborting.branches.head) shouldNotBeCalled else IO.unit
              }
          )
        )
        abortingWithHeadBranchAborted <- stateRef.get
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchAborted(branch: BID): IO[Unit] =
                stateRef.update(_.branchAborted(branch).fold(fail(_), identity))
            },
            Some(abortingWithHeadBranchAborted)
          )
        _ <- sideEffect.apply(Trigger.AfterRecovery, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Aborted(aborting.reason)))
      } yield ()
    }
  }

  test("do not repeat branch abort right after persisting the first abort confirmation") {
    forAllF(abortingGen) { aborting =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](
          aborting.branchAborted(aborting.branches.head).fold(fail(_), identity)
        )
        abortingWithHeadBranchAborted <- stateRef.get
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def abort(transactionID: TID, reason: AbortReason[R]): IO[Unit] =
                  shouldNotBeCalled
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {},
            Some(abortingWithHeadBranchAborted)
          )
        _ <- sideEffect.apply(Trigger.AfterPersistence, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Aborting(aborting.reason)))
      } yield ()
    }
  }

  test("branch abort failure (after persistence or recovery)") {
    forAllF(abortingGen, persistenceOrRecoveryTriggerGen) { (aborting, trigger: Trigger) =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](aborting)
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def abort(transactionID: TID, reason: AbortReason[R]): IO[Unit] =
                  IO.raiseError(new Exception("boom"))
              }
          )
        )
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchFailed(branch: BID, error: TID): IO[Unit] =
                stateRef.update(_.branchFailed(branch, error).fold(fail(_), identity))
            },
            Some(aborting)
          )
        _ <- sideEffect.apply(trigger, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status match {
          case Status.Failed(_) => true
          case _                => false
        }))
      } yield ()
    }
  }

  test("retries notifying self about preparation") {
    forAllF(preparingGen) { preparing =>
      for {
        stateRef <- IO.ref[TransactionState[TID, BID, Q, R]](preparing)
        sideEffect <- IO(
          new TransactionSideEffect[IO, TID, BID, Q, R](
            neverTimeout,
            (_: BID) =>
              new TestBranch {
                override def prepare(transactionID: TID, query: Q): IO[Vote[R]] = IO(Vote.Commit)

              }
          )
        )
        attemptsRef <- IO.ref(preparing.branches.map(_ -> 0).toMap)
        effector <- Effector
          .apply[IO, TransactionState[
            TID,
            BID,
            Q,
            R
          ], ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
            new SelfEntity {
              override def branchVoted(branch: BID, vote: Vote[R]): IO[Unit] =
                for {
                  attempt <- attemptsRef.get.map(_(branch))
                  _ <-
                    if (attempt < retryParameters.maxRetries)
                      attemptsRef.update(_.updated(branch, attempt + 1)) >> IO.raiseError(
                        new Exception("boom")
                      )
                    else
                      stateRef.update(_.branchVoted(branch, vote).fold(fail(_), identity))
                } yield ()
            },
            Some(preparing)
          )
        _ <- sideEffect.apply(Trigger.AfterRecovery, effector)
        _ <- assertIOBoolean(stateRef.get.map(_.status == Status.Committing))
      } yield ()
    }
  }

  trait TestBranch extends Branch[IO, TID, Q, R] {
    def prepare(transactionID: TID, query: Q): IO[Vote[R]] = shouldNotBeCalled
    def commit(transactionId: TID): IO[Unit] = shouldNotBeCalled
    def abort(transactionID: TID, reason: AbortReason[R]): IO[Unit] = shouldNotBeCalled
  }

  trait SelfEntity extends TransactionAlg[IO, TID, BID, Q, R] {
    def query: IO[Transaction.Unknown.type \/ Q] = shouldNotBeCalled
    def branches: IO[Transaction.Unknown.type \/ Set[BID]] = shouldNotBeCalled
    def status: IO[Transaction.Unknown.type \/ Status[R]] = shouldNotBeCalled
    def abort(reason: Option[R]): IO[Transaction.AbortError \/ Unit] = shouldNotBeCalled
    def branchVoted(branch: BID, vote: Vote[R]): IO[Unit] = shouldNotBeCalled
    def branchCommitted(branch: BID): IO[Unit] = shouldNotBeCalled
    def branchAborted(branch: BID): IO[Unit] = shouldNotBeCalled
    def branchFailed(branch: BID, error: TID): IO[Unit] = shouldNotBeCalled
    def timeout(): IO[Unit] = shouldNotBeCalled
    def create(
        id: TID,
        query: Q,
        branches: NonEmptyList[BID]
    ): IO[TransactionCreator.AlreadyExists.type \/ Unit] = shouldNotBeCalled
  }

}
