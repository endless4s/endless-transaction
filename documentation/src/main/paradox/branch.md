# Branch

```scala
trait Branch[F[_], TID, Q, R] 
  def prepare(id: TID, query: Q): F[Vote[R]]
  def commit(id: TID): F[Unit]
  def abort(id: TID): F[Unit]
```

A branch defines the behavior of the various phases of the 2PC protocol for a certain transaction type. The branch is responsible for preparing, committing, and aborting the transaction. The coordinator instantiates a branch for each transaction and branch ID. The branch provides an indirection to the actual effectful operations that are to be performed in the various phases of the protocol, such as sending messages to other services, updating databases, etc.

## Prepare
Prepare the branch for a transaction. This is the first step in the 2PC protocol. This operation supports an optional timeout (this is tracked by the coordinator), in which case the transaction will be aborted. However, there is no intrinsic limitation in the duration this operation may take. It could span days, as long as it returns a vote at some point and the coordinator is kept alive.

## Commit
Commit the transaction branch. This is the second step in the 2PC protocol. This operation should in principle not fail, all branches are expected to commit at this point to ensure consistency. It's up to the branch to organize retries if necessary.
As for prepare, there is no intrinsic limitation in the duration this operation may take. Confirmation is expected by the coordinator at some point to transition the transaction to committed, however.

## Abort
Abort the transaction branch. This is the second step in the 2PC protocol, in case of preparation timeout or any branch voting to abort. All branches are expected to abort at this point, failure could lead to local inconsistency. It's up to the branch to decide about that though, and organize retries if necessary. As for prepare and commit, there is no intrinsic limitation in the duration this operation may take. Confirmation is expected by the coordinator at some point to transition the transaction to aborted, however.

@@@ warning { title=Idempotency }
These operations are expected to be idempotent, as they may be retried by the coordinator.
@@@

@@@ note { title=Exceptions }
An exception raised in one of these operations transitions the transaction to failed state (once all branches return).
@@@ 