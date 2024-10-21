package endless.transaction.impl.logic

import cats.data.{Chain, NonEmptyList}
import cats.effect.IO
import endless.core.interpret.EntityT
import endless.transaction.helpers.LogMessageAssertions
import endless.transaction.impl.Generators
import endless.transaction.{Branch, Transaction}
import endless.transaction.impl.data.{TransactionEvent, TransactionState}
import org.scalacheck.Gen
import org.typelevel.log4cats.testing.TestingLogger
import org.scalacheck.effect.PropF.*

class TransactionEntityBehaviorSuite
    extends munit.CatsEffectSuite
    with munit.ScalaCheckEffectSuite
    with Generators
    with LogMessageAssertions {
  private implicit val logger: TestingLogger[IO] = TestingLogger.impl[IO]()

  private val behavior = new TransactionEntityBehavior(
    EntityT.instance[IO, TransactionState[TID, BID, Q, R], TransactionEvent[TID, BID, Q, R]]
  )
  private implicit val eventApplier: TransactionEventApplier[TID, BID, Q, R] =
    new TransactionEventApplier

  test("create transaction writes created event") {
    forAllF { (id: TID, query: Q, branchA: BID, branchB: BID) =>
      behavior
        .create(id, query, NonEmptyList.of(branchA, branchB))
        .run(None)
        .map {
          case Right((events, _)) =>
            assertEquals(
              events,
              Chain(TransactionEvent.Created(id, query, NonEmptyList.of(branchA, branchB)))
            )
          case Left(error) => fail(error)
        }
    }
  }

  test("retrieve transaction query") {
    forAllF { (state: TransactionState[TID, BID, Q, R]) =>
      behavior.query
        .run(Some(state))
        .map {
          case Right((_, Right(result)))             => assertEquals(result, state.query)
          case Right((_, Left(Transaction.Unknown))) => fail("Transaction should exist")
          case Left(error)                           => fail(error)
        }
    }
  }

  test("fail to retrieve transaction query when transaction does not exist") {
    forAllF { (id: TID) =>
      behavior.query
        .run(None)
        .map {
          case Right((_, Right(_)))                  => fail(s"Transaction $id should not exist")
          case Right((_, Left(Transaction.Unknown))) => ()
          case Left(error)                           => fail(error)
        }
    }
  }

  test("retrieve transaction branches") {
    forAllF { (state: TransactionState[TID, BID, Q, R]) =>
      behavior.branches
        .run(Some(state))
        .map {
          case Right((_, Right(result)))             => assertEquals(result, state.branches)
          case Right((_, Left(Transaction.Unknown))) => fail("Transaction should exist")
          case Left(error)                           => fail(error)
        }
    }
  }

  test("fail to retrieve transaction branches when transaction does not exist") {
    forAllF { (id: TID) =>
      behavior.branches
        .run(None)
        .map {
          case Right((_, Right(_)))                  => fail(s"Transaction $id should not exist")
          case Right((_, Left(Transaction.Unknown))) => ()
          case Left(error)                           => fail(error)
        }
    }
  }

  test("transaction status matches state") {
    forAllF { (state: TransactionState[TID, BID, Q, R]) =>
      behavior.status
        .run(Some(state))
        .map {
          case Right((_, Right(result)))             => assertEquals(result, state.status)
          case Right((_, Left(Transaction.Unknown))) => fail("Transaction should exist")
          case Left(error)                           => fail(error)
        }
    }
  }

  test("fail to retrieve transaction status when transaction does not exist") {
    forAllF { (id: TID) =>
      behavior.status
        .run(None)
        .map {
          case Right((_, Right(_)))                  => fail(s"Transaction $id should not exist")
          case Right((_, Left(Transaction.Unknown))) => ()
          case Left(error)                           => fail(error)
        }
    }
  }

  test("aborting transaction when preparing writes client aborted event") {
    forAllF { (state: TransactionState.Preparing[TID, BID, Q, R], reason: Option[R]) =>
      behavior
        .abort(reason)
        .run(Some(state))
        .map {
          case Right((events, _)) =>
            assertEquals(events, Chain(TransactionEvent.ClientAborted(reason)))
          case Left(error) => fail(error)
        }
    }
  }

  test("aborting transaction when committing or committed returns too late to abort") {
    forAllF(Gen.oneOf(committingGen, committedGen), Gen.option(rGen)) {
      (state: TransactionState[TID, BID, Q, R], reason: Option[R]) =>
        behavior
          .abort(reason)
          .run(Some(state))
          .map {
            case Right((_, Left(Transaction.TooLateToAbort(_)))) => ()
            case Right((_, _))                                   => fail("Unexpected")
            case Left(error)                                     => fail(error)
          }
    }
  }

  test("aborting transaction when aborting or aborted simply succeeds") {
    forAllF(Gen.oneOf(abortingGen, abortedGen), Gen.option(rGen)) {
      (state: TransactionState[TID, BID, Q, R], reason: Option[R]) =>
        behavior
          .abort(reason)
          .run(Some(state))
          .map {
            case Right((_, _)) => ()
            case Left(error)   => fail(error)
          }
    }
  }

  test("aborting transaction when failed returns transaction failed") {
    forAllF { (state: TransactionState.Failed[TID, BID, Q, R], reason: Option[R]) =>
      behavior
        .abort(reason)
        .run(Some(state))
        .map {
          case Right((_, Left(Transaction.TransactionFailed(_)))) => ()
          case Right((_, _))                                      => fail("Unexpected")
          case Left(error)                                        => fail(error)
        }
    }
  }

  test("aborting transaction when unknown returns transaction unknown") {
    forAllF { (reason: Option[R]) =>
      behavior
        .abort(reason)
        .run(None)
        .map {
          case Right((_, Left(Transaction.Unknown))) => ()
          case Right((_, _))                         => fail("Unexpected")
          case Left(error)                           => fail(error)
        }
    }
  }

  test("branch voted when preparing and branch has not voted yet writes branch voted event") {
    forAllF { (state: TransactionState.Preparing[TID, BID, Q, R], vote: Branch.Vote[R]) =>
      behavior
        .branchVoted(state.branches.head, vote)
        .run(Some(state))
        .map {
          case Right((events, _)) =>
            assertEquals(events, Chain(TransactionEvent.BranchVoted(state.branches.head, vote)))
          case Left(error) => fail(error)
        }
    }
  }

  test("branch voted when it has already voted discards the new vote and logs a warning") {
    forAllF {
      (
          state: TransactionState.Preparing[TID, BID, Q, R],
          branch: BID,
          vote: Branch.Vote[R],
          newVote: Branch.Vote[R]
      ) =>
        behavior
          .branchVoted(branch, newVote)
          .run(Some(state.copy(votes = state.votes.updated(branch, Some(vote)))))
          .flatMap {
            case Right((events, _)) =>
              assertIOBoolean(IO(events.isEmpty)) >> logger.assertLogsWarn
            case Left(error) => fail(error)
          }
    }
  }

  test("branch voted in aborting records the vote, even if it has no impact on the state") {
    forAllF(abortingGen, voteGen) {
      (state: TransactionState[TID, BID, Q, R], vote: Branch.Vote[R]) =>
        behavior
          .branchVoted(state.branches.head, vote)
          .run(Some(state))
          .map {
            case Right((events, _)) =>
              assertEquals(events, Chain(TransactionEvent.BranchVoted(state.branches.head, vote)))
            case Left(error) => fail(error)
          }
    }
  }

  test("branch voted in any other state discards the vote and logs a warning") {
    forAllF(
      Gen.oneOf(committingGen, committedGen, abortedGen, failedGen),
      voteGen
    ) { (state: TransactionState[TID, BID, Q, R], vote: Branch.Vote[R]) =>
      behavior
        .branchVoted(state.branches.head, vote)
        .run(Some(state))
        .flatMap {
          case Right((events, _)) => assertIOBoolean(IO(events.isEmpty)) >> logger.assertLogsWarn
          case Left(error)        => fail(error)
        }
    }
  }

  test("branch committed when committing writes branch committed event") {
    forAllF { (state: TransactionState.Committing[TID, BID, Q, R], branch: BID) =>
      behavior
        .branchCommitted(branch)
        .run(Some(state))
        .map {
          case Right((events, _)) =>
            assertEquals(events, Chain(TransactionEvent.BranchCommitted(branch)))
          case Left(error) => fail(error)
        }
    }
  }

  test("branch committed when already committed logs a warning") {
    forAllF { (state: TransactionState.Committing[TID, BID, Q, R], branch: BID) =>
      behavior
        .branchCommitted(branch)
        .run(Some(state.copy(commits = state.commits.updated(branch, true))))
        .flatMap {
          case Right((events, _)) =>
            assertIOBoolean(IO(events.isEmpty)) >> logger.assertLogsWarn
          case Left(error) => fail(error)
        }
    }
  }

  test("branch committed in any other state logs a warning") {
    forAllF(
      Gen.oneOf(preparingGen, abortingGen, abortedGen, failedGen),
      bidGen
    ) { (state: TransactionState[TID, BID, Q, R], branch: BID) =>
      behavior
        .branchCommitted(branch)
        .run(Some(state))
        .flatMap {
          case Right((events, _)) => assertIOBoolean(IO(events.isEmpty)) >> logger.assertLogsWarn
          case Left(error)        => fail(error)
        }
    }
  }

  test("branch aborted when aborting writes branch aborted event") {
    forAllF { (state: TransactionState.Aborting[TID, BID, Q, R], branch: BID) =>
      behavior
        .branchAborted(branch)
        .run(Some(state))
        .map {
          case Right((events, _)) =>
            assertEquals(events, Chain(TransactionEvent.BranchAborted(branch)))
          case Left(error) => fail(error)
        }
    }
  }

  test("branch aborted when already aborted logs a warning") {
    forAllF { (state: TransactionState.Aborting[TID, BID, Q, R], branch: BID) =>
      behavior
        .branchAborted(branch)
        .run(Some(state.copy(aborts = state.aborts.updated(branch, true))))
        .flatMap {
          case Right((events, _)) =>
            assertIOBoolean(IO(events.isEmpty)) >> logger.assertLogsWarn
          case Left(error) => fail(error)
        }
    }
  }

  test("branch aborted in any other state logs a warning") {
    forAllF(
      Gen.oneOf(preparingGen, committingGen, committedGen, failedGen),
      bidGen
    ) { (state: TransactionState[TID, BID, Q, R], branch: BID) =>
      behavior
        .branchAborted(branch)
        .run(Some(state))
        .flatMap {
          case Right((events, _)) => assertIOBoolean(IO(events.isEmpty)) >> logger.assertLogsWarn
          case Left(error)        => fail(error)
        }
    }
  }

  test("branch failed when in some pending state writes branch failed") {
    forAllF { (state: TransactionState.Pending[TID, BID, Q, R], branch: BID, error: String) =>
      behavior
        .branchFailed(branch, error)
        .run(Some(state))
        .map {
          case Right((events, _)) =>
            assertEquals(events, Chain(TransactionEvent.BranchFailed(branch, error)))
          case Left(error) => fail(error)
        }
    }
  }

  test("branch failed when in some final state other than failed logs a warning") {
    forAllF(Gen.oneOf(committedGen, abortedGen), bidGen, Gen.alphaNumStr) {
      (state, branch, error) =>
        behavior
          .branchFailed(branch, error)
          .run(Some(state))
          .flatMap {
            case Right((events, _)) => assertIOBoolean(IO(events.isEmpty)) >> logger.assertLogsWarn
            case Left(error)        => fail(error)
          }
    }
  }

  test("transaction timeout when preparing writes transaction timeout event") {
    forAllF { (state: TransactionState.Preparing[TID, BID, Q, R]) =>
      behavior
        .timeout()
        .run(Some(state))
        .map {
          case Right((events, _)) => assertEquals(events, Chain(TransactionEvent.Timeout))
          case Left(error)        => fail(error)
        }
    }
  }

  test("transaction timeout in any other state logs a warning") {
    forAllF(
      Gen.oneOf(committingGen, committedGen, abortingGen, abortedGen, failedGen)
    ) { (state: TransactionState[TID, BID, Q, R]) =>
      behavior
        .timeout()
        .run(Some(state))
        .flatMap {
          case Right((events, _)) => assertIOBoolean(IO(events.isEmpty)) >> logger.assertLogsWarn
          case Left(error)        => fail(error)
        }
    }
  }
}
