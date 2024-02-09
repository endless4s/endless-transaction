# Two-phase commit protocol
The three tenants of the 2PC protocol are the *prepare*, *commit* and *abort*: in `endless-transaction`, participating branches defines effectful expressions for the three operations.

## Prepare
Validate a *query* value, possibly effect the local branch context and return a *vote* value. The vote is a signal for the coordinator to either *commit* or *abort* the transaction. This expression can involve any kind of asynchronous process, and there is no limit for the time it takes for the vote to be delivered to the coordinator (unless a timeout is configured). Some examples of prepare operations in an imaginary (and somewhat convoluted) touristic journey booking process:

| Data store         | Transaction branch operation                                           | Example: orchestration of the booking process for a journey                          |
|--------------------|------------------------------------------------------------------------|--------------------------------------------------------------------------------------| 
| External API       | Make a reversible synchronous HTTP POST/PUT request                    | Create cancelable hotel & flight reservations                                        |
| Internal service   | Send a message or call an endpoint and wait for an event to occur      | Request the billing back-end for credit card guarantee charge and await confirmation |
| Database           | Acquire an exclusive lock on a database row or use built-in XA support | Add details to the row corresponding to the customer in the internal database        |
| In-memory resource | Lock an in-memory resource                                             | Grab a semaphore to update the "recent bookings" cache                               |
| Actor cluster      | Send a command to an actor                                             | Schedule traveller reminder notifications                                            |
| File               | Persist a rollback-ready change in a file                              | Add an entry in a bookings log                                                       |

## Commit
This is triggered by the coordinator after all branches have voted for commit. The branch is expected to effect the local context to make at least part of the change durable and return a *confirmation* value.

Like for *prepare* above, this expression can involve any kind of asynchronous process, and the toolkit does not impose a limit for the time it takes for the confirmation to be delivered to the coordinator.

Some examples of commit operations in the same imaginary touristic journey booking process:

| Data store         | Transaction branch operation                           | Example: orchestration of the booking process for a journey   |
|--------------------|--------------------------------------------------------|---------------------------------------------------------------|
| External API       | Send a message or call and endpoint of another service | Do nothing: reservations were already made                    |
| Internal service   | Send a message to another service                      | Do nothing: the guarantee charge was already made             |
| Database           | Perform the row update and unlock                      | Update customer details in the internal database              |
| In-memory resource | Edit and unlock an in-memory resource                  | Update the "recent bookings" cache and release the semaphore  |
| Actor cluster      | Send a command to an actor                             | Do nothing: the reminder notifications were already scheduled |
| File               | Persist a change in a file                             | Do nothing: the bookings log was already updated              |

@@@ Note
It's up to the implementer to decide the level of consistency in execution of the commit. Transaction failure is also valid state and can be signalled by raising an exception in the target effect. Obviously, this will lead to some degree of inconsistency in the overall system state, but that can be an acceptable compromise in some use cases.
For instance, in our imaginary example the "in-memory cache" could be locked only for a short time to preserve throughput. Because it's not essential to the journey process, updating it could be skipped in case of delays. On the other hand, if the "internal database" update would still fail in spite of the lock, this could be surfaced by  raising an exception. This would conclude the transaction in a failed state, which could be surfaced in the UI to allow for manual remediation.

## Abort

This is triggered by the coordinator after at least one branch has voted for abort. The branch is expected to effect the local context to roll back the changes and return a *confirmation* value. The same flexibility as for *prepare* and *commit* applies here.

Some examples of abort operations in the same imaginary process:

| Data store         | Transaction branch operation                           | Example: orchestration of the booking process for a journey                 |
|--------------------|--------------------------------------------------------|-----------------------------------------------------------------------------|
| External API       | Send a message or call and endpoint of another service | Cancel the hotel & flight reservations                                      |
| Internal service   | Send a message to another service                      | Cancel the credit guarantee charge                                          |
| Database           | Unlock the row and do nothing                          | Do nothing: the customer details do not need to be updated                  |
| In-memory resource | Unlock an in-memory resource                           | Release the semaphore and do nothing: the cache does not need to be updated |
| Actor cluster      | Send a command to an actor                             | Cancel the reminder notifications                                           |
| File               | Roll back the change in a file                         | Roll back the booking log entry                                             |

The same argument applies here as for the commit operation: it's up to the implementer to decide the level of consistency in execution of the abort. For example, if  reminders have already been sent, a compensation action can be to schedule a different notification inviting the customer to ignore previous messages. On the other hand, failing to cancel the hotel and flight reservations would be a more serious issue and should be surfaced as a failed transaction.

@@@ Warning
Certain use cases call for more specific techniques: for instance, the classical challenge of publishing events to a broker atomically with a change in a database is best solved by the transaction outbox pattern or event sourcing.
@@@

@@@ Info
 - Distributed databases such as CockroachDB, MongoDB and others implement 2PC to atomically store values across partitions.
 - Apache Kafka allows producing messages accross multiple partitions atomically with an implementation similar to 2PC.
@@@

## State diagram

Protocol state throughout the aforementioned phases is tracked by an event-sourced entity, with events representing transitions of the state. The transaction state machine diagram is depicted below. As usual, side-effects are invoked after successful event persistence, and repeated in case of recovery (at least once delivery characteristics).

<img src="diagrams/TransactionEntity.png"/>