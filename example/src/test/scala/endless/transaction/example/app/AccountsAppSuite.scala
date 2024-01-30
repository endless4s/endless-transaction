package endless.transaction.example.app

import cats.effect.IO
import cats.syntax.show.*
import com.comcast.ip4s.*
import endless.transaction.example.Generators
import endless.transaction.example.data.{AccountID, PosAmount}
import munit.ScalaCheckEffectSuite
import org.http4s.Uri
import org.http4s.client.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.scalacheck.effect.PropF.forAllF
import org.http4s.Method.*
import org.http4s.Uri.Path.SegmentEncoder

import scala.concurrent.duration.*

class AccountsAppSuite extends munit.CatsEffectSuite with ScalaCheckEffectSuite with Generators {
  lazy val port: Port = port"8081"
  private val pekkoServer = ResourceSuiteLocalFixture("pekko-server", AccountsApp(port))
  private val client =
    ResourceSuiteLocalFixture("client", EmberClientBuilder.default[IO].build)
  private lazy val baseUri = Uri.unsafeFromString(s"http://localhost:$port") / "account"
  implicit private lazy val segmentEncoder: SegmentEncoder[AccountID] =
    SegmentEncoder[String].contramap(_.value)

  override def munitTimeout: Duration = 1.minute

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

  override def munitFixtures: Seq[Fixture[?]] = List(pekkoServer, client)
}
