# In a nutshell

While the traditional [two-phase commit protocol (2PC)](https://en.wikipedia.org/wiki/Two-phase_commit_protocol) is synchronous and blocking, `endless-transaction` is designed to be asynchronous and non-blocking and features fine-grained failure and retry semantics. It can be used as a ready-made tool to achieve various degrees of consistency in distributed systems, relying behind the scenes on an actor cluster for scalability and resilience.

The three tenants of the 2PC protocol are *prepare*, *commit*, and *abort*: in endless-transaction, participating branches have full flexibility in defining effectful expressions for the three operations. For certain domains, strong consistency and locking might be required. For others, some degree of inconsistency can be tolerated. It can even be a mix of both in a single transaction. The lifetime of the transaction isn't limited either, transaction timeout tracking is optional.

Diverse forms of two-phase consensus appear frequently in service-oriented systems, especially when involving complex business workflows and heterogeneous data stores. Its usefulness goes beyond the specific area of strongly consistent, atomic transactions such as [XA](https://en.wikipedia.org/wiki/X/Open_XA). [Long-running transactions](https://en.wikipedia.org/wiki/Long-running_transaction), also known as sagas, typically make use of rollback or undo operations in the abort phase, also called [compensating transactions](https://en.wikipedia.org/wiki/Compensating_transaction).

Abstractions in the library are implemented by one of the two available runtimes, Pekko or Akka. Internally, transactions are materialized with a persistent sharded entity implementing the two-phase protocol asynchronously with at least once delivery guarantee.

@@@ note { .tip title="For more info" }
Check out the blog article [Two-phase consensus with functional Scala](https://jonas-chapuis.medium.com/two-phase-consensus-with-functional-scala-5dc29388ac5a)
@@@