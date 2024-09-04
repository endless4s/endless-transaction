# Coordinator

```scala
trait Coordinator[F[_], TID, BID, Q, R] 
  def create(
      id: TID,
      query: Q,
      branch: BID,
      otherBranches: BID*
  ): Resource[F, Transaction[F, BID, Q, R]]
```

@scaladoc[Coordinator](endless.transaction.Coordinator) allows for creating and recovering transactions of a certain type. Internally, it implements the two-phase commit protocol to guarantee a final transaction state of either committed or aborted.

The `create` method creates a new transaction with the given id, query and branches, and returns a `Transaction` wrapped in a `Resource`. Resource release leads to transaction abort if it is still pending (or a no-op if the transaction is already completed). 

Parameters of `create` are:

 - `id`: the transaction identifier
 - `query`: the payload sent to branches
 - `branch...`: identifiers for the branches involved in the transaction 

@@@ warning { title=Compatibility }
Behavior for each branch is defined by the `branchForID` function passed to the `Coordinator` upon creation. This is invoked upon node restart for any pending transaction. It is therefore important to consider compatibility aspects if any change is done on this behavior. 

The transaction might be in a partial preparation readiness state, and rollout of the new version will pick up preparation where it left off. As long as the new behavior is compatible with the old, this is not a problem. 
@@@