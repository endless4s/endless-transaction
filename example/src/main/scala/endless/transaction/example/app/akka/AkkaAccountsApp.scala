package endless.transaction.example.app.akka

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.flatMap.*
import com.comcast.ip4s.Port
import endless.core.entity.{EntityNameProvider, Sharding}
import endless.core.interpret.{BehaviorInterpreter, SideEffectInterpreter}
import endless.core.protocol.EntityIDCodec
import endless.runtime.akka.deploy.AkkaCluster
import endless.runtime.akka.deploy.AkkaDeployer.AkkaDeploymentParameters
import endless.runtime.akka.syntax.deploy.*
import endless.transaction.example.adapter.AccountEventAdapter
import endless.transaction.example.algebra.{Account, Accounts}
import endless.transaction.example.app.HttpServer
import endless.transaction.example.data.{AccountEvent, AccountID, AccountState, TransferParameters}
import endless.transaction.example.logic.{
  AccountEntityBehavior,
  AccountEventApplier,
  AccountProtocol,
  ShardedAccounts
}
import endless.transaction.example.proto.events
import endless.transaction.akka.AkkaTransactor
import akka.actor.typed.ActorSystem
import akka.persistence.typed.{EventAdapter, EventSeq}
import akka.util.Timeout
import endless.transaction.example.helpers.RetryHelpers.RetryParameters
import org.http4s.server.Server
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.*

object AkkaAccountsApp {
  private implicit val accountEntityNameProvider: EntityNameProvider[AccountID] = () => "account"
  private implicit val accountIDCodec: EntityIDCodec[AccountID] =
    EntityIDCodec(_.value, AccountID(_))
  private implicit val eventApplier: AccountEventApplier = new AccountEventApplier
  private implicit val commandProtocol: AccountProtocol = new AccountProtocol
  private implicit val transferParameters: TransferParameters =
    TransferParameters(
      timeout = 30.seconds,
      TransferParameters.BranchRetryParameters(
        onError = RetryParameters(1.second, 5),
        onPendingTransfer = RetryParameters(200.millis, 5)
      )
    )
  private implicit val askTimeout: Timeout = Timeout(30.seconds)
  private val terminationTimeout = 5.seconds
  private lazy val pekkoEventAdapter = new EventAdapter[AccountEvent, events.AccountEvent] {
    private val eventAdapter = new AccountEventAdapter
    override def toJournal(e: AccountEvent): events.AccountEvent = eventAdapter.toJournal(e)
    def manifest(e: AccountEvent): String = e.getClass.getName
    override def fromJournal(e: events.AccountEvent, manifest: String): EventSeq[AccountEvent] =
      EventSeq.single(eventAdapter.fromJournal(e))
  }

  def apply(httpPort: Port)(implicit system: ActorSystem[Nothing]): Resource[IO, Server] =
    createAkkaApp >>= (deployment => HttpServer(httpPort, deployment.repository))

  private def createAkkaApp(implicit actorSystem: ActorSystem[Nothing]) =
    Resource.eval(Slf4jLogger.create[IO]) >>= { implicit logger =>
      AkkaCluster.managedResource[IO](actorSystem, terminationTimeout, terminationTimeout) >>= {
        implicit cluster: AkkaCluster[IO] =>
          implicit val transactor: AkkaTransactor[IO] = AkkaTransactor[IO]
          implicit val deploymentParameters
              : AkkaDeploymentParameters[IO, AccountState, AccountEvent] =
            AkkaDeploymentParameters[IO, AccountState, AccountEvent](customizeBehavior =
              (_, behavior) => behavior.eventAdapter(pekkoEventAdapter)
            )
          deployRepository[IO, AccountID, AccountState, AccountEvent, Account, Accounts](
            (sharding: Sharding[IO, AccountID, Account]) =>
              ShardedAccounts
                .transfersCoordinator(sharding)
                .map(coordinator => new ShardedAccounts(sharding, coordinator)),
            BehaviorInterpreter.lift(AccountEntityBehavior(_)),
            SideEffectInterpreter.unit
          )
      }
    }

}
