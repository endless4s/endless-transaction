package endless.transaction.example.app

import cats.effect.{ExitCode, IO, IOApp}
import com.comcast.ip4s.*

object Main extends IOApp {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def run(args: List[String]): IO[ExitCode] =
    AccountsApp(port"8080").useForever.as(ExitCode.Success)
}
