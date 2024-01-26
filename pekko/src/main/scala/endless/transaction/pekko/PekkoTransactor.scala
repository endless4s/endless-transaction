package endless.transaction.pekko

import cats.Show
import cats.effect.kernel.{Async, Resource}
import endless.runtime.pekko.deploy.PekkoCluster
import endless.runtime.pekko.deploy.PekkoDeployer.PekkoDeploymentParameters
import endless.runtime.pekko.syntax.deploy.DeploymentParameters
import endless.transaction.impl.adapter.TransactionEventAdapter
import endless.transaction.impl.data.{TransactionEvent, TransactionState}
import endless.transaction.proto
import endless.transaction.proto.events
import endless.transaction.{BinaryCodec, Branch, Coordinator, StringCodec, Transactor}
import org.apache.pekko.cluster.sharding.typed.ClusterShardingSettings
import org.apache.pekko.persistence.typed.{EventAdapter, EventSeq}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*
import org.apache.pekko.util.Timeout

class PekkoTransactor[F[_]: Async: Logger](implicit pekkoCluster: PekkoCluster[F])
    extends Transactor[F] {
  private implicit val askTimeout: Timeout = Timeout(1.minute)

  def coordinator[
      TID: StringCodec: BinaryCodec,
      BID: BinaryCodec: Show,
      Q: BinaryCodec,
      R: BinaryCodec
  ](
      transactionName: String,
      branchForID: BID => Branch[F, TID, BID, Q, R],
      timeout: Option[FiniteDuration]
  ): Resource[F, Coordinator[F, TID, BID, Q, R]] = {
    type S = TransactionState[TID, BID, Q, R]
    type E = TransactionEvent[TID, BID, Q, R]
    val eventAdapter = new TransactionEventAdapter[TID, BID, Q, R]()
    implicit val pekkoDeploymentParameters: DeploymentParameters[F, TID, S, E] = {
      PekkoDeploymentParameters[F, S, E](
        customizeBehavior = (_, behavior) =>
          behavior.eventAdapter(
            new EventAdapter[TransactionEvent[TID, BID, Q, R], proto.events.TransactionEvent] {
              override def toJournal(
                  event: TransactionEvent[TID, BID, Q, R]
              ): proto.events.TransactionEvent =
                eventAdapter.toJournal(event)

              def manifest(event: TransactionEvent[TID, BID, Q, R]): String = event.getClass.getName

              def fromJournal(
                  p: events.TransactionEvent,
                  manifest: String
              ): EventSeq[TransactionEvent[TID, BID, Q, R]] =
                EventSeq.single(eventAdapter.fromJournal(p))
            }
          ),
        customizeEntity =
          // we can't leave transactions dangling, otherwise e.g. we can lock up participating entities or have partial commits
          _.withSettings(ClusterShardingSettings(pekkoCluster.system).withRememberEntities(true))
      )
    }
    deployEntityBasedCoordinator(
      transactionName,
      branchForID,
      timeout,
      endless.runtime.pekko.syntax.deploy
    ).map(_.repository)
  }
}

object PekkoTransactor {
  def apply[F[_]: Async: Logger: PekkoCluster]: PekkoTransactor[F] = new PekkoTransactor[F]
}
