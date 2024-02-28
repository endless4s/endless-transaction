package endless.transaction.impl.logic
import cats.data.NonEmptyList
import endless.transaction.Branch.Vote
import endless.transaction.Transaction.AbortReason
import endless.transaction.impl.data.TransactionEvent.*
import endless.transaction.impl.data.TransactionState
import endless.transaction.impl.data.TransactionState.*
import org.scalacheck.Prop.*
import org.scalacheck.cats.implicits.*
import cats.instances.list.*
import cats.syntax.traverse.*
import endless.transaction.impl.Generators
import org.scalacheck.Gen

class TransactionEventApplierSuite extends munit.ScalaCheckSuite with Generators {
  property("transaction created when empty leads to preparing") {
    forAll { (tid: TID, query: Q, branchA: BID, branchB: BID) =>
      val fold =
        new TransactionEventApplier()(None, Created(tid, query, NonEmptyList.of(branchA, branchB)))
      assertEquals(fold, Right(Some(Preparing(tid, Map(branchA -> None, branchB -> None), query))))
    }
  }

  property("transaction created when non-empty leads to error") {
    forAll { (tid: TID, preparing: Preparing[TID, BID, Q, R]) =>
      val fold =
        new TransactionEventApplier()(
          Some(preparing),
          Created(tid, preparing.query, NonEmptyList.fromListUnsafe(preparing.branches.toList))
        )
      assert(fold.isLeft)
    }
  }

  property("branch voted when preparing registers the vote") {
    forAll { (preparing: TransactionState.Preparing[TID, BID, Q, R], vote: Vote[R]) =>
      val fold =
        new TransactionEventApplier()(Some(preparing), BranchVoted(preparing.branches.head, vote))
      assertEquals(
        fold,
        Right(
          Some(
            Preparing(
              preparing.id,
              preparing.votes.updated(preparing.branches.head, Some(vote)),
              preparing.query
            )
          )
        )
      )
    }
  }

  property("all branches voting commit leads to committing") {
    forAll { (preparing: TransactionState.Preparing[TID, BID, Q, R]) =>
      val applier = new TransactionEventApplier[TID, BID, Q, R]()
      val fold = preparing.branches.foldLeft(preparing: TransactionState[TID, BID, Q, R]) {
        case (state, branch) =>
          applier.apply(Some(state), BranchVoted(branch, Vote.Commit)).fold(fail(_), _.get)
      }
      assertEquals(
        fold,
        Committing[TID, BID, Q, R](
          preparing.id,
          preparing.branches.map(_ -> false).toMap,
          preparing.query
        )
      )
    }
  }

  property("at least one branch voting abort leads to aborting") {
    forAll(for {
      preparing <- preparingGen
      votes <- preparing.branches.toList
        .traverse(bid => voteGen.map(bid -> _))
        .map(_.toMap)
        .suchThat(
          _.values.exists(_.isInstanceOf[Vote.Abort[R]])
        )
    } yield (preparing, votes)) {
      case (preparing: TransactionState.Preparing[TID, BID, Q, R], votes) =>
        val applier = new TransactionEventApplier[TID, BID, Q, R]()
        val fold =
          preparing.branches.foldLeft(preparing: TransactionState[TID, BID, Q, R]) {
            case (state, branch) =>
              applier
                .apply(Some(state), BranchVoted(branch, votes(branch)))
                .fold(fail(_), _.get)
          }
        assertEquals(
          fold,
          Aborting[TID, BID, Q, R](
            preparing.id,
            preparing.branches.map(_ -> false).toMap,
            preparing.query,
            AbortReason.Branches(NonEmptyList.fromListUnsafe(votes.values.collect {
              case Vote.Abort(reason) => reason
            }.toList))
          )
        )
    }
  }

  property("branch voted in a final state leads to error") {
    forAll { (finalState: TransactionState.Final[TID, BID, Q, R], vote: Vote[R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(finalState),
        BranchVoted(finalState.branches.head, vote)
      )
      assert(fold.isLeft)
    }
  }

  property("branch voted when committing leads to error") {
    forAll { (committing: TransactionState.Committing[TID, BID, Q, R], vote: Vote[R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(committing),
        BranchVoted(committing.branches.head, vote)
      )
      assert(fold.isLeft)
    }
  }

  property("branch voted when aborting is ignored") {
    forAll { (aborting: TransactionState.Aborting[TID, BID, Q, R], vote: Vote[R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(aborting),
        BranchVoted(aborting.branches.head, vote)
      )
      assertEquals(fold, Right(Some(aborting)))
    }
  }

  property("client aborting when preparing leads to aborting") {
    forAll { (preparing: TransactionState.Preparing[TID, BID, Q, R], reason: Option[R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(preparing),
        ClientAborted(reason)
      )
      assertEquals(
        fold,
        Right(
          Some(
            Aborting(
              preparing.id,
              preparing.branches.map(_ -> false).toMap,
              preparing.query,
              AbortReason.Client(reason)
            )
          )
        )
      )
    }
  }

  property("client aborting in any other state than preparing leads to error") {
    forAll(
      Gen.oneOf(committedGen, abortedGen, failedGen, committingGen, abortingGen),
      Gen.option(rGen)
    ) { (state: TransactionState[TID, BID, Q, R], reason: Option[R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(state),
        ClientAborted(reason)
      )
      assert(fold.isLeft)
    }
  }

  property("branch committed when committing registers the commit") {
    forAll { (committing: TransactionState.Committing[TID, BID, Q, R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(committing),
        BranchCommitted(committing.branches.head)
      )
      assertEquals(
        fold,
        Right(
          Some(
            Committing[TID, BID, Q, R](
              committing.id,
              committing.commits.updated(committing.branches.head, true),
              committing.query
            )
          )
        )
      )
    }
  }

  property("branch committed in any other state than committing leads to error") {
    forAll(
      Gen.oneOf(preparingGen, committedGen, abortedGen, failedGen, abortingGen),
      bidGen
    ) { (state: TransactionState[TID, BID, Q, R], branch: BID) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(state),
        BranchCommitted(branch)
      )
      assert(fold.isLeft)
    }
  }

  property("all branches committed leads to committed") {
    forAll { (committing: TransactionState.Committing[TID, BID, Q, R]) =>
      val applier = new TransactionEventApplier[TID, BID, Q, R]()
      val fold = committing.branches.foldLeft(committing: TransactionState[TID, BID, Q, R]) {
        case (state, branch) =>
          applier.apply(Some(state), BranchCommitted(branch)).fold(fail(_), _.get)
      }
      assertEquals(
        fold,
        Committed[TID, BID, Q, R](
          committing.id,
          committing.query,
          committing.branches
        )
      )
    }
  }

  property("branch aborted when aborting registers the abort") {
    forAll { (aborting: TransactionState.Aborting[TID, BID, Q, R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(aborting),
        BranchAborted(aborting.branches.head)
      )
      assertEquals(
        fold,
        Right(
          Some(
            Aborting[TID, BID, Q, R](
              aborting.id,
              aborting.aborts.updated(aborting.branches.head, true),
              aborting.query,
              aborting.reason
            )
          )
        )
      )
    }
  }

  property("branch aborted in any other state than aborting leads to error") {
    forAll(
      Gen.oneOf(preparingGen, committedGen, abortedGen, failedGen, committingGen),
      bidGen
    ) { (state: TransactionState[TID, BID, Q, R], branch: BID) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(state),
        BranchAborted(branch)
      )
      assert(fold.isLeft)
    }
  }

  property("all branches aborted leads to aborted") {
    forAll { (aborting: TransactionState.Aborting[TID, BID, Q, R]) =>
      val applier = new TransactionEventApplier[TID, BID, Q, R]()
      val fold = aborting.branches.foldLeft(aborting: TransactionState[TID, BID, Q, R]) {
        case (state, branch) =>
          applier.apply(Some(state), BranchAborted(branch)).fold(fail(_), _.get)
      }
      assertEquals(
        fold,
        Aborted[TID, BID, Q, R](
          aborting.id,
          aborting.query,
          aborting.branches,
          aborting.reason
        )
      )
    }
  }

  property("branch failed in pending state leads to failed") {
    forAll { (pending: TransactionState.Pending[TID, BID, Q, R], branch: BID, error: String) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(pending),
        BranchFailed(branch, error)
      )
      assertEquals(
        fold,
        Right(Some(TransactionState.Failed.branch(pending, branch, error)))
      )
    }
  }

  property("branch failed in aborted or committed state leads to error") {
    forAll(
      Gen.oneOf(abortedGen, committedGen),
      bidGen,
      Gen.alphaNumStr
    ) { (state: TransactionState[TID, BID, Q, R], branch: BID, error: String) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(state),
        BranchFailed(branch, error)
      )
      assert(fold.isLeft)
    }
  }

  property("branch failed in failed state adds the error") {
    forAll { (failed: TransactionState.Failed[TID, BID, Q, R], branch: BID, error: String) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(failed),
        BranchFailed(branch, error)
      )
      assertEquals(
        fold,
        Right(Some(failed.copy(branchErrors = failed.branchErrors.updated(branch, error))))
      )
    }
  }

  property("timeout in preparing state leads to aborting") {
    forAll { (preparing: TransactionState.Preparing[TID, BID, Q, R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(preparing),
        Timeout
      )
      assertEquals(
        fold,
        Right(
          Some(
            Aborting[TID, BID, Q, R](
              preparing.id,
              preparing.branches.map(_ -> false).toMap,
              preparing.query,
              AbortReason.Timeout
            )
          )
        )
      )
    }
  }

  property("timeout in any other state leads to error") {
    forAll(
      Gen.oneOf(committedGen, abortedGen, failedGen, committingGen, abortingGen)
    ) { (state: TransactionState[TID, BID, Q, R]) =>
      val fold = new TransactionEventApplier[TID, BID, Q, R]()(
        Some(state),
        Timeout
      )
      assert(fold.isLeft)
    }
  }

}
