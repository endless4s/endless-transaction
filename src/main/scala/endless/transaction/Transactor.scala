package endless.transaction

import cats.Show
import cats.effect.kernel.{Async, Resource}
import endless.core.entity.{Deployer, EntityNameProvider}
import endless.core.event.EventApplier
import endless.core.interpret.{BehaviorInterpreter, RepositoryInterpreter}
import endless.core.protocol.CommandProtocol
import endless.transaction.impl.algebra.TransactionAlg
import endless.transaction.impl.data.{TransactionEvent, TransactionState}
import endless.transaction.impl.helpers.RetryHelpers.RetryParameters
import endless.transaction.impl.logic.{
  ShardedCoordinator,
  TransactionEntityBehavior,
  TransactionEventApplier,
  TransactionProtocol,
  TransactionSideEffect
}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.FiniteDuration

/** The transactor is the entry point provided by the underlying runtime to create distributed
  * transaction coordinators for any type of transaction.
  * @tparam F
  *   the effect type
  */
trait Transactor[F[_]] {

  /** Create a transaction coordinator for a given transaction type.
    *
    * @param transactionName
    *   the name of the transaction: this is used as the entity name for persistence
    * @param branchForID
    *   a function to get the branch behavior for a given branch ID. A branch describes what to do
    *   in the prepare, commit and abort phases of the transaction. In other words, this defines the
    *   various "sides" of the transaction. Branch behavior is differentiated by branch ID. No
    *   constraints are set on the effects a branch can have: it can describe interactions with
    *   heterogeneous systems via http, multiple entities in a cluster, etc.
    * @param timeout
    *   an optional timeout duration for the transaction. When defined, elapsed time is tracked
    *   during the prepare phase and timing out leads to abort with timeout reason.
    * @tparam TID
    *   the transaction ID type: this has to be a unique identifier for the transaction and is used
    *   for sharding.
    * @tparam BID
    *   the branch ID type: this identifies uniquely a participating branch in the transaction.
    *   Since it is used to access the branch behavior, it is used to differentiate the behavior for
    *   each branch.
    * @tparam Q
    *   the query type
    * @tparam R
    *   the abort reason type
    * @return
    *   a resource representing the transaction coordinator deployment. Since transactions are
    *   persistent and potentially require recovery upon startup, a coordinator needs to run during
    *   the lifetime of the application for a given transaction type.
    */
  def coordinator[
      TID: StringCodec: BinaryCodec,
      BID: BinaryCodec: Show,
      Q: BinaryCodec,
      R: BinaryCodec
  ](
      transactionName: String,
      branchForID: BID => Branch[F, TID, Q, R],
      timeout: Option[FiniteDuration] = None
  ): Resource[F, Coordinator[F, TID, BID, Q, R]]

  /** Deploy a transaction coordinator based on an endless entity. This is an internal protected
    * method than can be used by the runtime to implement the coordinator deployment.
    * @param transactionName
    *   the name of the transaction
    * @param branchForID
    *   a function to get the branch for a given branch ID
    * @param timeout
    *   the timeout for the transaction
    * @param deployer
    *   the endless entity deployer
    * @tparam TID
    *   the transaction ID type
    * @tparam BID
    *   the branch ID type
    * @tparam Q
    *   the query type
    * @tparam R
    *   the abort reason type
    * @return
    *   a resource with the deployed coordinator
    */
  protected def deployEntityBasedCoordinator[
      TID: StringCodec: BinaryCodec,
      BID: BinaryCodec: Show,
      Q: BinaryCodec,
      R: BinaryCodec
  ](
      transactionName: String,
      branchForID: BID => Branch[F, TID, Q, R],
      timeout: Option[FiniteDuration],
      deployer: Deployer
  )(implicit
      retryParameters: RetryParameters,
      deploymentParameters: deployer.DeploymentParameters[F, TID, TransactionState[
        TID,
        BID,
        Q,
        R
      ], TransactionEvent[TID, BID, Q, R]],
      async: Async[F],
      logger: Logger[F]
  ): Resource[F, deployer.Deployment[F, ({ type C[G[_]] = Coordinator[G, TID, BID, Q, R] })#C]] = {
    type S = TransactionState[TID, BID, Q, R]
    type E = TransactionEvent[TID, BID, Q, R]
    type Alg[K[_]] = TransactionAlg[K, TID, BID, Q, R]
    type RepositoryAlg[K[_]] = Coordinator[K, TID, BID, Q, R]
    implicit val entityNameProvider: EntityNameProvider[TID] = () => transactionName
    implicit val protocol: CommandProtocol[TID, Alg] = new TransactionProtocol[TID, BID, Q, R]
    implicit val eventApplier: EventApplier[S, E] = new TransactionEventApplier[TID, BID, Q, R]
    deployer
      .deployRepository[F, TID, S, E, Alg, RepositoryAlg](
        RepositoryInterpreter.lift[F, TID, Alg, RepositoryAlg](
          new ShardedCoordinator[F, TID, BID, Q, R](_)
        ),
        BehaviorInterpreter.lift[F, S, E, Alg](new TransactionEntityBehavior(_)),
        { case (_, transactionAlg) => TransactionSideEffect(timeout, branchForID, transactionAlg) }
      )

  }
}
