package endless.transaction.pekko
import com.typesafe.config.ConfigFactory
import endless.transaction.impl.helpers.RetryHelpers.RetryParameters

import scala.concurrent.duration.*
import scala.concurrent.duration.FiniteDuration

final case class Config(retries: Config.RetryConfig, askTimeout: FiniteDuration)

object Config {
  final case class RetryConfig(initialDelay: FiniteDuration, maxRetries: Int) {
    lazy val parameters: RetryParameters = RetryParameters(initialDelay, maxRetries)
  }

  def load(): Config = {
    val config = ConfigFactory.load()
    val retriesConfig = config.getConfig("endless.transaction.pekko.retries")
    val initialDelay = retriesConfig.getDuration("initial-delay", MILLISECONDS).millis
    val maxRetries = retriesConfig.getInt("max-retries")

    val askTimeout = config.getDuration("endless.transaction.pekko.ask-timeout", SECONDS).seconds
    Config(RetryConfig(initialDelay, maxRetries), askTimeout)
  }

}
