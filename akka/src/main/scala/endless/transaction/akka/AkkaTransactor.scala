package endless.transaction.akka

import akka.persistence.typed.{EventAdapter, EventSeq}
import akka.util.Timeout
import cats.Show
import cats.effect.kernel.{Async, Resource}
import endless.runtime.akka.deploy.AkkaCluster
import endless.runtime.akka.deploy.AkkaDeployer.AkkaDeploymentParameters
import endless.runtime.akka.syntax.deploy.DeploymentParameters
import endless.transaction.impl.adapter.TransactionEventAdapter
import endless.transaction.impl.data.{TransactionEvent, TransactionState}
import endless.transaction.proto.events
import endless.transaction.*
import endless.transaction.impl.helpers.RetryHelpers
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*

class AkkaTransactor[F[_]: Async: Logger](implicit akkaCluster: AkkaCluster[F])
    extends Transactor[F] {

  def coordinator[
      TID: StringCodec: BinaryCodec,
      BID: BinaryCodec: Show,
      Q: BinaryCodec,
      R: BinaryCodec
  ](
      transactionName: String,
      branchForID: BID => Branch[F, TID, Q, R],
      timeout: Option[FiniteDuration]
  ): Resource[F, Coordinator[F, TID, BID, Q, R]] = {
    type S = TransactionState[TID, BID, Q, R]
    type E = TransactionEvent[TID, BID, Q, R]
    val config = Config.load()
    implicit val askTimeout: Timeout = Timeout(config.askTimeout)
    val eventAdapter = new TransactionEventAdapter[TID, BID, Q, R]()
    implicit val akkaDeploymentParameters: DeploymentParameters[F, TID, S, E] = {
      AkkaDeploymentParameters[F, S, E](
        customizeBehavior = (_, behavior) =>
          behavior.eventAdapter(
            new EventAdapter[TransactionEvent[TID, BID, Q, R], proto.events.TransactionEvent] {
              override def toJournal(
                  event: TransactionEvent[TID, BID, Q, R]
              ): proto.events.TransactionEvent = eventAdapter.toJournal(event)

              def manifest(event: TransactionEvent[TID, BID, Q, R]): String = event.getClass.getName

              def fromJournal(
                  p: events.TransactionEvent,
                  manifest: String
              ): EventSeq[TransactionEvent[TID, BID, Q, R]] =
                EventSeq.single(eventAdapter.fromJournal(p))
            }
          )
      )
    }
    implicit val retryParameters: RetryHelpers.RetryParameters = config.retries.parameters
    deployEntityBasedCoordinator(
      transactionName,
      branchForID,
      timeout,
      endless.runtime.akka.syntax.deploy
    ).map(_.repository)
  }
}

object AkkaTransactor {

  /** Create a new AkkaTransactor
    * @param akkaCluster
    *   the AkkaCluster
    * @tparam F
    *   the effect type
    * @return
    *   a new AkkaTransactor
    */
  def apply[F[_]: Async: Logger](implicit akkaCluster: AkkaCluster[F]): AkkaTransactor[F] =
    new AkkaTransactor[F]
}
