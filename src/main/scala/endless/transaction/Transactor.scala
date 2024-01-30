package endless.transaction

import cats.Show
import cats.effect.kernel.{Async, Resource}
import endless.core.entity.{Deployer, EntityNameProvider}
import endless.core.event.EventApplier
import endless.core.interpret.{BehaviorInterpreter, RepositoryInterpreter}
import endless.core.protocol.CommandProtocol
import endless.transaction.impl.algebra.TransactionAlg
import endless.transaction.impl.data.{TransactionEvent, TransactionState}
import endless.transaction.impl.logic.{
  ShardedCoordinator,
  TransactionEntityBehavior,
  TransactionEventApplier,
  TransactionProtocol,
  TransactionSideEffect
}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.FiniteDuration

trait Transactor[F[_]] {

  def coordinator[
      TID: StringCodec: BinaryCodec,
      BID: BinaryCodec: Show,
      Q: BinaryCodec,
      R: BinaryCodec
  ](
      transactionName: String,
      branchForID: BID => Branch[F, TID, BID, Q, R],
      timeout: Option[FiniteDuration] = None
  ): Resource[F, Coordinator[F, TID, BID, Q, R]]

  protected def deployEntityBasedCoordinator[
      TID: StringCodec: BinaryCodec,
      BID: BinaryCodec: Show,
      Q: BinaryCodec,
      R: BinaryCodec
  ](
      transactionName: String,
      branchForID: BID => Branch[F, TID, BID, Q, R],
      timeout: Option[FiniteDuration],
      deployer: Deployer
  )(implicit
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
