# Transactor

```scala
trait Transactor[F[_]] 
  def coordinator[
      TID: StringCodec: BinaryCodec,
      BID: BinaryCodec: Show,
      Q: BinaryCodec,
      R: BinaryCodec
  ](transactionName: String,
    branchForID: BID => Branch[F, TID, Q, R],
    timeout: Option[FiniteDuration] = None
  ): Resource[F, Coordinator[F, TID, BID, Q, R]]
```

@scaladoc[Transactor](endless.transaction.Transactor) is the entry point provided by the runtime to create distributed transaction coordinators for any type of transaction.  Particular transaction types are defined by the type parameters of the `coordinator` method: `TID` is the transaction identifier, `BID` is the branch identifier, `Q` is the query type, and `R` is the abort reason type.

The `coordinator` method creates a `Coordinator` for a given transaction type. Its parameters are:

 - `transactionName`: used as the entity name for persistence.
 - `branchForID`: a function that describes the branch behavior for a given branch ID. A `Branch` describes what to do in the prepare, commit, and abort phases of the transaction. In other words, this defines the various "sides" of the transaction. Branch behavior can be differentiated by branch ID. No constraints are set on the effects a branch can have: it can describe interactions with heterogeneous systems via HTTP, multiple entities in a cluster, etc.
 - `timeout`: optional timeout for transaction preparation. When defined, elapsed time is tracked internally during the prepare phase, and timing out leads to transaction abort with timeout reason. 
