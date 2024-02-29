# Example

The example app is a dummy API for managing bank accounts and transferring amounts between accounts. Accounts are implemented with sharded entities, and transfers with transactions. 

The example app can be found in the `example` module and can be run directly with `sbt run`. 

@@@ note { title="Stress-test" }
The module also contains a multi-jvm test that demonstrates the resilience of the system, by transferring amounts between 1000 accounts while restarting nodes.  
@@@

## API

The API has a simple CRUD for accounts and a dedicated endpoint to perform transfers between two accounts.

```scala
HttpRoutes.of[IO] {
    case GET -> Root / "account" / id / "balance" => server.balanceFor(AccountID(id))
    case POST -> Root / "account" / id            => server.open(AccountID(id))
    case POST -> Root / "account" / id / "deposit" / IntVar(amount) => server.deposit(AccountID(id), amount)
    case POST -> Root / "account" / id / "withdraw" / IntVar(amount) => server.withdraw(AccountID(id), amount)
    case POST -> Root / "account" / origin / "transfer" / "to" / destination / IntVar(amount) => server.transfer(AccountID(origin), AccountID(destination), PosAmount(amount))
  }
  .orNotFound
```

The HTTP server makes uses of the `Accounts` and `Account` algebras to satisfy the requests.

## Algebras
### `Accounts` 
This trait represents the repository, i.e. the ability to retrieve a handle on a specific account and orchestrate transfers between accounts.   
```scala
trait Accounts[F[_]] 
  def accountFor(name: AccountID): Account[F]
  def transfer(from: AccountID, to: AccountID, amount: PosAmount): F[TransferFailure \/ Unit]
```

### `Account` 
This trait represents the account entity, i.e. the ability to query and perform operations on a specific account. 
```scala
trait Account[F[_]] 
  def open: F[AlreadyExists.type \/ Unit]
  def balance: F[Unknown.type \/ NonNegAmount]
  def deposit(amount: PosAmount): F[Unknown.type \/ PosAmount]
  def withdraw(amount: PosAmount): F[WithdrawFailure \/ NonNegAmount]

  def prepareOutgoingTransfer(id: TransferID, transfer: Transfer): F[WithdrawFailure \/ Unit]
  def prepareIncomingTransfer(id: TransferID, transfer: Transfer): F[IncomingTransferFailure \/ Unit]
  def commitTransfer(id: TransferID): F[TransferFailure \/ Unit]
  def abortTransfer(id: TransferID): F[TransferFailure \/ Unit]
```

The latter four methods are of particular interest as they are used by the transactional branch logic to carry out the various phases of the transfer. 

## Transfers
Transfers are implemented using an `endless-transaction` transaction coordinator.  

### Coordinator
A coordinator is created with `TransferID` as the transaction identifier type, `AccountID` as the branch identifier, `Transfer` as the query payload (the amount, origin, and destination), and `TransferFailure` as the error coproduct.

@@snip [Coordinator](/example/src/main/scala/endless/transaction/example/logic/ShardedAccounts.scala) { #create-coordinator }

The coordinator is used in the implementation of the `transfer` method in `ShardedAccounts`, i.e. the implementation of `Accounts`. 

### Transaction
The snippet below shows the logic: the code creates a transfer with two branches, one for the origin account, and the other for the destination account.

@@snip [ShardedAccounts](/example/src/main/scala/endless/transaction/example/logic/ShardedAccounts.scala) { #transfer }

The transaction entity is sharded, so it can be running on a separate node. We therefore need to regularly check for completion so that we can respond to the API call (it's synchronous here for the sake of simplicity). 

To implement this polling operation, we use the `pollForFinalStatus()` built-in extension method (defined for `Transaction`): this method retrieves the status of the transaction at configurable intervals and semantically sleeps in between.

### Branch
An account's involvement in a transfer is described by `TransferBranch`, as exemplified below with the implementation of the `prepare` method: 

@@snip [Prepare](/example/src/main/scala/endless/transaction/example/logic/TransferBranch.scala) { #example }
