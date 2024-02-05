package endless.transaction.example.app

import cats.effect.{ExitCode, IO, IOApp}
import com.comcast.ip4s.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitDurableStateStorePlugin,
  PersistenceTestKitPlugin
}

object Main extends IOApp {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def run(args: List[String]): IO[ExitCode] = {
    (for {
      executionContext <- IO.executionContext.toResource
      actorSystem <- IO(
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
      ).toResource
      app <- AccountsApp(port"8080")(actorSystem)
    } yield app).useForever.as(ExitCode.Success)
  }

}
