package endless.transaction.impl.data

import cats.data.NonEmptyList
import cats.syntax.either.*
import endless.\/
import endless.transaction.Branch.Vote
import endless.transaction.Transaction.*

private[transaction] sealed trait TransactionState[TID, BID, Q, R] {
  def id: TID
  def branches: Set[BID]
  def query: Q
  def status: Status[R]

  def branchVoted(branch: BID, vote: Vote[R]): String \/ TransactionState[TID, BID, Q, R]
  def clientAborted(reason: Option[R]): String \/ TransactionState[TID, BID, Q, R]
  def timeout(): String \/ TransactionState[TID, BID, Q, R]
  def branchCommitted(branch: BID): String \/ TransactionState[TID, BID, Q, R]
  def branchAborted(branch: BID): String \/ TransactionState[TID, BID, Q, R]
  def branchFailed(branch: BID, error: String): String \/ TransactionState[TID, BID, Q, R]
}

private[transaction] object TransactionState {
  sealed trait Pending[TID, BID, Q, R] extends TransactionState[TID, BID, Q, R] {
    def branchFailed(branch: BID, error: String): String \/ TransactionState[TID, BID, Q, R] =
      Failed.branch(this, branch, error).asRight
  }

  sealed trait Final[TID, BID, Q, R] extends TransactionState[TID, BID, Q, R] {
    def branchVoted(branch: BID, vote: Vote[R]): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot vote after transaction has finished".asLeft

    def clientAborted(reason: Option[R]): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot abort after transaction has finished".asLeft

    def timeout(): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot timeout after transaction has finished".asLeft

    def branchCommitted(branch: BID): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot commit after transaction has finished".asLeft

    def branchAborted(branch: BID): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot abort after transaction has finished".asLeft

    def branchFailed(branch: BID, error: String): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot fail after transaction has finished".asLeft
  }

  final case class Preparing[TID, BID, Q, R](
      id: TID,
      votes: Map[BID, Option[Vote[R]]],
      query: Q
  ) extends Pending[TID, BID, Q, R] {
    def branches: Set[BID] = votes.keySet

    def status: Status[R] = Status.Preparing

    def clientAborted(reason: Option[R]): String \/ TransactionState[TID, BID, Q, R] =
      aborting(AbortReason.Client(reason)).asRight

    def timeout(): String \/ TransactionState[TID, BID, Q, R] = aborting(
      AbortReason.Timeout
    ).asRight

    private def aborting(reason: AbortReason[R]): Aborting[TID, BID, Q, R] =
      Aborting(id, branches.map(_ -> false).toMap, query, reason)

    def branchVoted(branch: BID, vote: Vote[R]): String \/ TransactionState[TID, BID, Q, R] = {
      if (hasBranchAlreadyVoted(branch)) "Branch has already voted".asLeft
      else if (!branches.contains(branch)) "Branch is not participating in the transaction".asLeft
      else {
        val updated = copy(votes = votes.updated(branch, Some(vote)))
        if (!updated.haveAllVoted) updated.asRight
        else {
          NonEmptyList
            .fromList(updated.abortVotes)
            .map(abortingByBranches)
            .getOrElse(updated.committing)
            .asRight
        }
      }
    }

    def hasBranchAlreadyVoted(branch: BID): Boolean = votes.get(branch).exists(_.isDefined)

    private def abortingByBranches(reasons: NonEmptyList[R]) =
      Aborting(id, branches.map(_ -> false).toMap, query, AbortReason.Branches(reasons))

    private def committing: TransactionState[TID, BID, Q, R] =
      Committing(id, votes.keySet.map(_ -> false).toMap, query)

    private def abortVotes = votes.values.collect { case Some(Vote.Abort(reason)) =>
      reason
    }.toList

    private def haveAllVoted: Boolean = votes.values.forall(_.isDefined)

    def branchCommitted(branch: BID): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot commit yet".asLeft

    def branchAborted(branch: BID): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot confirm abort yet".asLeft

  }

  final case class Committing[TID, BID, Q, R](
      id: TID,
      commits: Map[BID, Boolean],
      query: Q
  ) extends Pending[TID, BID, Q, R] {
    def branches: Set[BID] = commits.keySet

    def status: Status[R] = Status.Committing

    def branchVoted(branch: BID, vote: Vote[R]): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot vote after commit".asLeft

    def clientAborted(reason: Option[R]): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot abort during commit".asLeft

    def timeout(): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot timeout during commit".asLeft

    def branchCommitted(branch: BID): String \/ TransactionState[TID, BID, Q, R] =
      if (hasBranchAlreadyCommitted(branch)) "Branch has already committed".asLeft
      else {
        val updated: Committing[TID, BID, Q, R] = copy(commits = commits.updated(branch, true))
        if (!updated.haveAllCommitted) updated.asRight
        else (Committed(id, query, branches): TransactionState[TID, BID, Q, R]).asRight
      }

    def hasBranchAlreadyCommitted(branch: BID): Boolean =
      commits.get(branch).exists(identity)

    private def haveAllCommitted: Boolean = commits.values.forall(identity)

    def branchAborted(branch: BID): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot confirm abort after commit".asLeft

  }

  final case class Committed[TID, BID, Q, R](
      id: TID,
      query: Q,
      branches: Set[BID]
  ) extends Final[TID, BID, Q, R] {
    def status: Status[R] = Status.Committed
  }

  final case class Aborting[TID, BID, Q, R](
      id: TID,
      aborts: Map[BID, Boolean],
      query: Q,
      reason: AbortReason[R]
  ) extends Pending[TID, BID, Q, R] {

    def branches: Set[BID] = aborts.keySet

    def status: Status[R] = Status.Aborting(reason)

    def branchVoted(branch: BID, vote: Vote[R]): String \/ TransactionState[TID, BID, Q, R] =
      this.asRight // ignored

    def clientAborted(reason: Option[R]): String \/ TransactionState[TID, BID, Q, R] =
      "Already aborting".asLeft

    def timeout(): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot timeout while aborting".asLeft

    def branchCommitted(branch: BID): String \/ TransactionState[TID, BID, Q, R] =
      "Cannot be committing while aborting".asLeft

    def branchAborted(branch: BID): String \/ TransactionState[TID, BID, Q, R] =
      if (hasBranchAlreadyAborted(branch)) "Branch has already aborted".asLeft
      else {
        val updated: Aborting[TID, BID, Q, R] = copy(aborts = aborts.updated(branch, true))
        if (!updated.haveAllAborted) updated.asRight
        else (Aborted(id, query, branches, reason): TransactionState[TID, BID, Q, R]).asRight
      }

    def hasBranchAlreadyAborted(branch: BID): Boolean = aborts.get(branch).exists(identity)

    private def haveAllAborted: Boolean = aborts.values.forall(identity)
  }

  final case class Aborted[TID, BID, Q, R](
      id: TID,
      query: Q,
      branches: Set[BID],
      reason: AbortReason[R]
  ) extends Final[TID, BID, Q, R] {
    def status: Status[R] = Status.Aborted(reason)
  }

  final case class Failed[TID, BID, Q, R](
      originState: Pending[TID, BID, Q, R],
      branchErrors: Map[BID, String]
  ) extends Final[TID, BID, Q, R] {
    def id: TID = originState.id

    def branches: Set[BID] = originState.branches

    def query: Q = originState.query

    def status: Status[R] = Status.Failed(NonEmptyList.fromListUnsafe(branchErrors.values.toList))

    override def branchFailed(
        branch: BID,
        error: String
    ): String \/ TransactionState[TID, BID, Q, R] =
      Failed(originState, branchErrors.updated(branch, error)).asRight

  }

  object Failed {
    def branch[TID, BID, Q, R](
        originState: Pending[TID, BID, Q, R],
        branch: BID,
        error: String
    ): Failed[TID, BID, Q, R] =
      Failed(originState, Map(branch -> error))
  }
}
