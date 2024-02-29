package endless.transaction.example

import cats.effect.{IO, Ref}
import cats.effect.kernel.Resource
import cats.effect.std.Random
import cats.effect.unsafe.implicits.global
import cats.instances.list.*
import cats.syntax.eq.*
import cats.syntax.parallel.*
import cats.syntax.show.*
import com.comcast.ip4s.*
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import endless.transaction.example.Common.*
import endless.transaction.example.app.pekko.PekkoAccountsApp
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.http4s.Method.*
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.client.middleware.{Retry, RetryPolicy}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{Request, Response, Uri}
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.IterableHasAsJava

class StressSuiteMultiJvmNode1 extends AnyFunSuite {
  test(
    "Node 1: triggers account creation and transfers and stays up long enough to ensure a cluster exists."
  ) {
    val httpPort = httpPorts.head
    val arteryPort = arteryPorts.head
    val baseUri = Uri.unsafeFromString(s"http://localhost:$httpPort") / "account"
    (for {
      logger <- Slf4jLogger.create[IO].toResource
      client <- httpClient
      implicit0(system: ActorSystem[Nothing]) <- IO.executionContext
        .map(actorSystemFor(arteryPort, _))
        .toResource
      _ <- logger.info("Creating the database journal").toResource
      _ <- initDb.toResource
      _ <- PekkoAccountsApp(httpPort)
      _ <- logger.info("Waiting for cluster formation").toResource
      _ <- IO.sleep(clusterFormationWaitingTime).toResource
      _ <- logger.info("Creating origin accounts").toResource
      _ <- originAccounts
        .parTraverse(id =>
          client
            .status(POST(baseUri / id))
            .map(_.isSuccess)
            .map(assert(_, s"Failed to create account $id"))
        )
        .toResource
      _ <- logger.info("Depositing to origin accounts").toResource
      _ <- originAccounts
        .parTraverse(id =>
          client
            .status(POST(baseUri / id / "deposit" / originAmount))
            .map(_.isSuccess)
            .map(assert(_, s"Failed to deposit to account $id"))
        )
        .toResource
      _ <- logger.info("Creating destination accounts").toResource
      _ <- destinationAccounts
        .parTraverse(id =>
          client
            .status(POST(baseUri / id))
            .map(_.isSuccess)
            .map(assert(_, s"Failed to create account $id"))
        )
        .toResource
      _ <- logger.info("Starting transfers from origin accounts to destination accounts").toResource
      _ <- 1
        .to(accountsCount)
        .toList
        .parTraverse { index =>
          val origin = s"origin$index"
          val destination = s"destination$index"
          client
            .status(POST(baseUri / origin / "transfer" / "to" / destination / transferAmount))
            .map(_.isSuccess)
            .map(assert(_, s"Failed to transfer from $origin to $destination"))
        }
        .toResource
      _ <- logger.info("All transfers completed").toResource
      assertion <- IO
        .race(
          waitForExpectedBalanceOnDestinationAccounts(
            client,
            baseUri,
            accountsCount,
            originAmount - transferAmount
          ),
          IO.sleep(testTimeout)
        )
        .toResource
      - <- logger
        .info("Waiting just a bit more to ensure cluster is stable to allow all nodes to succeed")
        .toResource
      _ <- IO.sleep(atLeastOneNodeUpWaitingTime).toResource
      _ <- logger.info("Shutting down").toResource
    } yield assert(
      assertion.fold(_ => true, _ => false),
      "Timed out waiting for transfers to complete as expected"
    )).use_.unsafeRunSync()
  }
}

class StressSuiteMultiJvmNode2 extends AnyFunSuite {
  test("Node 2: verifies that balance on destination accounts is correct, restarts periodically") {
    restartingPassiveNode(httpPorts(1), arteryPorts(1)).unsafeRunSync()
  }
}

class StressSuiteMultiJvmNode3 extends AnyFunSuite {
  test("Node 3: verifies that balance on destination accounts is correct, restarts periodically") {
    restartingPassiveNode(httpPorts(2), arteryPorts(2)).unsafeRunSync()
  }
}

object Common {
  val httpPorts: List[Port] = List(port"8081", port"8082", port"8083")
  val arteryPorts: List[Port] = List(port"51000", port"51001", port"51002")

  val dbInitializationWaitingTime = 2.seconds
  val clusterFormationWaitingTime = 2.seconds
  val atLeastOneNodeUpWaitingTime = 3.minutes
  val nodeRestartWaitingTime = 5.seconds
  val minRestartDelaySeconds = 5
  val maxRestartDelaySeconds = 20
  val testTimeout = atLeastOneNodeUpWaitingTime + 1.minute
  val checkRetryTimeout = 5.seconds
  val accountsCount = 1000
  val originAmount = 10
  val transferAmount = 5
  val originAccounts = 1.to(accountsCount).map(index => s"origin$index").toList
  val destinationAccounts = 1.to(accountsCount).map(index => s"destination$index").toList

  def actorSystemFor(arteryPort: Port, executionContext: ExecutionContext): ActorSystem[Nothing] =
    ActorSystem.wrap(
      org.apache.pekko.actor.ActorSystem(
        name = "example-pekko-as",
        config = Some(
          ConfigFactory
            .load()
            .withValue(
              "pekko.remote.artery.canonical.port",
              ConfigValueFactory.fromAnyRef(arteryPort.value)
            )
            .withValue(
              "pekko.cluster.seed-nodes",
              ConfigValueFactory.fromIterable(
                arteryPorts
                  .map(port => show"pekko://example-pekko-as@127.0.0.1:${port.value}")
                  .asJava
              )
            )
        ),
        defaultExecutionContext = Some(executionContext),
        classLoader = None
      )
    )

  lazy val httpClient: Resource[IO, Client[IO]] = EmberClientBuilder
    .default[IO]
    .build
    .map(client =>
      Retry.create(
        RetryPolicy(
          RetryPolicy.exponentialBackoff(5.seconds, 10),
          (_: Request[IO], result: Either[Throwable, Response[IO]]) =>
            RetryPolicy.recklesslyRetriable[IO](result)
        ),
        logRetries = false
      )(client)
    )

  def initDb(implicit actorSystem: ActorSystem[Nothing]): IO[Unit] = for {
    _ <- IO.fromFuture(IO.blocking(SchemaUtils.dropIfExists()))
    _ <- IO.fromFuture(IO.blocking(SchemaUtils.createIfNotExists()))
  } yield ()

  def restartingPassiveNode(httpPort: Port, arteryPort: Port): IO[Unit] = {
    lazy val restartingNode: IO[Unit] = for {
      logger <- Slf4jLogger.create[IO]
      checkSuccessful <- Ref.of[IO, Boolean](false)
      random <- Random.scalaUtilRandom[IO]
      restartPeriod <- random
        .betweenInt(minRestartDelaySeconds, maxRestartDelaySeconds)
        .map(_.seconds)
      _ <- IO.race(
        passiveNode(httpPort, arteryPort, checkSuccessful)
          .handleErrorWith((error: Throwable) => logger.error(error)("Node hard fail").toResource)
          .use_,
        IO.sleep(restartPeriod)
      )
      _ <- checkSuccessful.get.flatMap {
        case true => logger.info("Balance check successful, terminating...")
        case false =>
          logger.info("Restarting node...") >> IO.sleep(nodeRestartWaitingTime) >> restartingNode
      }
    } yield ()
    IO.race(restartingNode, IO.sleep(testTimeout))
      .map(assertion =>
        assert(
          assertion.fold(_ => true, _ => false),
          "Timed out waiting for transfers to complete as expected"
        )
      )
  }

  private def passiveNode(
      httpPort: Port,
      arteryPort: Port,
      checkSuccessful: Ref[IO, Boolean]
  ): Resource[IO, Unit] =
    for {
      logger <- Slf4jLogger.create[IO].toResource
      client <- httpClient
      _ <- logger.info("Wait for DB initialization").toResource
      _ <- IO.sleep(dbInitializationWaitingTime).toResource
      implicit0(system: ActorSystem[Nothing]) <- IO.executionContext
        .map(actorSystemFor(arteryPort, _))
        .toResource
      _ <- PekkoAccountsApp(httpPort)
      _ <- logger.info("Waiting for cluster formation").toResource
      _ <- IO.sleep(clusterFormationWaitingTime).toResource
      _ <- waitForExpectedBalanceOnDestinationAccounts(
        client,
        Uri.unsafeFromString(s"http://localhost:$httpPort") / "account",
        accountsCount,
        originAmount - transferAmount
      ).toResource
      _ <- checkSuccessful.set(true).toResource
      _ <- logger.info("Shutting down").toResource
    } yield ()

  def waitForExpectedBalanceOnDestinationAccounts(
      client: Client[IO],
      baseUri: Uri,
      accountsCount: Int,
      expectedBalance: Int
  ): IO[Unit] = {
    lazy val retry = IO.sleep(checkRetryTimeout) >> waitForExpectedBalanceOnDestinationAccounts(
      client,
      baseUri,
      accountsCount,
      expectedBalance
    )
    checkBalanceOfDestinationAccounts(client, baseUri, expectedBalance)
      .flatMap(if (_) IO.unit else retry)
      .handleErrorWith(error =>
        Slf4jLogger
          .create[IO]
          .flatMap(_.error(error)("Error checking balances, retrying...")) >> retry
      )
  }

  private def checkBalanceOfDestinationAccounts(
      client: Client[IO],
      baseUri: Uri,
      expectedBalance: Int
  ): IO[Boolean] = for {
    logger <- Slf4jLogger.create[IO]
    _ <- logger.info("Checking balances of destination accounts")
    balances <- destinationAccounts.parTraverse(id =>
      client.expect[String](GET(baseUri / id / "balance")).map(_.toInt)
    )
    allExpected <- IO(balances.forall(_ === expectedBalance))
    _ <-
      if (allExpected) logger.info("All destination accounts have the expected balance")
      else logger.info("Not all destination accounts have the expected balance yet")
  } yield allExpected

}
