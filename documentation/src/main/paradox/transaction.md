# Transaction

```scala
trait Transaction[F[_], BID, Q, R] {
  def query: F[Unknown.type \/ Q]
  def branches: F[Unknown.type \/ Set[BID]]
  def status: F[Unknown.type \/ Status[R]]
  def abort(reason: Option[R] = None): F[AbortError \/ Unit]
}
```

@scaladoc[Transaction](endless.transaction.Transaction) is the handle to the transaction sharded entity, which can be used to inspect its status, retrieve its query and participating branches, and trigger a client abort.

The various states of the transaction are:

| State      | Description                                                                                                                                                                                                                                  | Side-effect (upon transition into state or recovery)                  |
|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Preparing  | Prepares have been issued by the coordinator, which is now waiting for votes from the participating branches.                                                                                                                                | Send prepare messages (to branches that have yet to vote).            |
| Committing | All participating branches have voted in favor of transaction commit. Branch commits have been issued by the coordinator, which is now waiting for branch commit confirmations.                                                              | Send commit messages (to branches that have yet to confirm committed) |
| Committed  | Transaction has successfully completed, all participating branches have confirmed the commit.                                                                                                                                                | N/A                                                                   |
| Aborting   | At least one participating branch voted against transaction commit, or an abort has been requested by the client in the meantime. Branch aborts have been issued by the coordinator which is now waiting for confirmation from the branches. | Send abort messages (to branches that have yet to confirm aborted).   |
| Aborted    | Transaction has been aborted and all participating branches have confirmed the abort                                                                                                                                                         | N/A                                                                   |
| Failed     | Transaction has failed due to an exception raised by one of the participating branches                                                                                                                                                       | N/A                                                                   |

<img src="diagrams/TransactionEntity.png"/>