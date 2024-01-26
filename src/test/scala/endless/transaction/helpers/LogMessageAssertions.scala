package endless.transaction.helpers

import cats.effect.IO
import munit.{Assertions, CatsEffectAssertions}
import org.typelevel.log4cats.testing.TestingLogger
import org.typelevel.log4cats.testing.TestingLogger.{DEBUG, ERROR, INFO, WARN}

trait LogMessageAssertions extends CatsEffectAssertions { self: Assertions =>
  implicit class LoggerAssertions(testingLogger: TestingLogger[IO]) {
    def assertLogsWarn: IO[Unit] =
      assertIOBoolean(testingLogger.logged.map(_.exists {
        case WARN(_, _) => true
        case _          => false
      }))

    def assertLogsError: IO[Unit] =
      assertIOBoolean(testingLogger.logged.map(_.exists {
        case ERROR(_, _) => true
        case _           => false
      }))

    def assertLogsInfo: IO[Unit] =
      assertIOBoolean(testingLogger.logged.map(_.exists {
        case INFO(_, _) => true
        case _          => false
      }))

    def assertLogsDebug: IO[Unit] =
      assertIOBoolean(testingLogger.logged.map(_.exists {
        case DEBUG(_, _) => true
        case _           => false
      }))
  }
}
