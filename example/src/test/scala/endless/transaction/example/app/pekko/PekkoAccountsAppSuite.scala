package endless.transaction.example.app.pekko

import cats.effect.IO
import cats.syntax.flatMap.*
import cats.syntax.show.*
import com.comcast.ip4s.*
import com.typesafe.config.ConfigFactory
import endless.transaction.example.Generators
import endless.transaction.example.data.{AccountID, PosAmount}
import munit.{AnyFixture, ScalaCheckEffectSuite}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitDurableStateStorePlugin,
  PersistenceTestKitPlugin
}
import org.http4s.Method.*
import org.http4s.Uri
import org.http4s.Uri.Path.SegmentEncoder
import org.http4s.client.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.scalacheck.effect.PropF.forAllF

import scala.concurrent.duration.*

class PekkoAccountsAppSuite
    extends munit.CatsEffectSuite
    with ScalaCheckEffectSuite
    with Generators {
  lazy val port: Port = port"8081"
  private val pekkoServer =
    ResourceSuiteLocalFixture(
      "pekko-server",
      IO.executionContext.toResource >>= { executionContext =>
        PekkoAccountsApp(port)(
          ActorSystem.wrap(
            org.apache.pekko.actor.ActorSystem(
              name = "example-pekko-as",
              config = Some(
                PersistenceTestKitPlugin.config
                  .withFallback(PersistenceTestKitDurableStateStorePlugin.config)
                  .withFallback(ConfigFactory.defaultApplication)
                  .resolve()
              ),
              defaultExecutionContext = Some(executionContext),
              classLoader = None
            )
          )
        )
      }
    )
  private lazy val client =
    ResourceSuiteLocalFixture("client", EmberClientBuilder.default[IO].build)
  private lazy val baseUri = Uri.unsafeFromString(s"http://localhost:$port") / "account"
  implicit private lazy val segmentEncoder: SegmentEncoder[AccountID] =
    SegmentEncoder[String].contramap(_.value)

  override val munitIOTimeout: Duration = 2.minutes

  test("depositing/withdrawing to/from account increases/decreases balance") {
    forAllF { (id: AccountID, amount: PosAmount) =>
      for {
        _ <- client().status(POST(baseUri / id))
        _ <- client().status(POST(baseUri / id / "deposit" / amount.show))
        _ <- assertIO(
          client().expect[String](GET(baseUri / id / "balance")).map(_.toInt),
          amount.value
        )
        _ <- client().status(POST(baseUri / id / "withdraw" / amount.show))
        balance <- client().expect[String](GET(baseUri / id / "balance")).map(_.toInt)
      } yield assertEquals(balance, 0)
    }
  }

  test("transferring from one account to another") {
    forAllF(for {
      origin <- accountIDGen
      destination <- accountIDGen.suchThat(_ != origin)
      amount <- posAmountGen
    } yield (origin, destination, amount)) {
      case (origin: AccountID, destination: AccountID, amount: PosAmount) =>
        for {
          _ <- client().status(POST(baseUri / origin))
          _ <- client().status(POST(baseUri / destination))
          _ <- client().status(POST(baseUri / origin / "deposit" / amount.show))
          _ <- client().status(
            POST(baseUri / origin / "transfer" / "to" / destination / amount.show)
          )
          _ <- assertIO(
            client()
              .expect[String](GET(baseUri / origin / "balance"))
              .map(_.toInt),
            0
          )
          _ <- assertIO(
            client()
              .expect[String](GET(baseUri / destination / "balance"))
              .map(_.toInt),
            amount.value
          )
          _ <- client().status(
            POST(baseUri / destination / "withdraw" / amount.show)
          ) // clear the account
        } yield ()
    }
  }

  test("depositing to non-existing account fails") {
    for {
      status <- client().status(POST(baseUri / "unknown" / "deposit" / 10))
    } yield assertEquals(status.code, 400)
  }

  test("withdrawing from non-existing account fails") {
    for {
      status <- client().status(POST(baseUri / "unknown" / "withdraw" / 10))
    } yield assertEquals(status.code, 400)
  }

  test("balance for non-existing account fails") {
    for {
      status <- client().status(GET(baseUri / "unknown" / "balance"))
    } yield assertEquals(status.code, 400)
  }

  test("transferring from non-existing origin account fails") {
    for {
      _ <- client().status(POST(baseUri / "destination"))
      status <- client().status(POST(baseUri / "unknown" / "transfer" / "to" / "destination" / 10))
    } yield assertEquals(status.code, 400)
  }

  test("transferring to non-existing destination account fails") {
    for {
      _ <- client().status(POST(baseUri / "origin"))
      status <- client().status(POST(baseUri / "origin" / "transfer" / "to" / "unknown" / 10))
    } yield assertEquals(status.code, 400)
  }

  test("withdrawing from account with insufficient funds fails") {
    for {
      _ <- client().status(POST(baseUri / "account"))
      _ <- client().status(POST(baseUri / "account" / "deposit" / 1))
      status <- client().status(POST(baseUri / "account" / "withdraw" / 2))
    } yield assertEquals(status.code, 400)
  }

  test("transferring from account with insufficient funds fails") {
    for {
      _ <- client().status(POST(baseUri / "origin"))
      _ <- client().status(POST(baseUri / "destination"))
      _ <- client().status(POST(baseUri / "origin" / "deposit" / 1))
      status <- client().status(POST(baseUri / "origin" / "transfer" / "to" / "destination" / 2))
    } yield assertEquals(status.code, 400)
  }

  override def munitFixtures: Seq[AnyFixture[?]] = List(pekkoServer, client)
}
