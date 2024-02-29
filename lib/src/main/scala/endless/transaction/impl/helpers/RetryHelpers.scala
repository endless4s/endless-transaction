package endless.transaction.impl.helpers

import cats.effect.kernel.Temporal
import cats.syntax.applicativeError.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import scala.concurrent.duration.*

object RetryHelpers {
  final case class RetryParameters(initialDelay: Duration, maxRetries: Int)

  trait OnError[F[_]] {
    def apply(error: Throwable, delay: Duration, retryAttempt: Int, maxRetries: Int): F[Unit]
  }

  implicit class RetryOps[F[_], A](fa: F[A]) {
    def retryWithBackoff(
        onError: OnError[F]
    )(implicit retryParameters: RetryParameters, temporal: Temporal[F]): F[A] = {
      import retryParameters.*
      retryWithBackoffImpl(onError, initialDelay, retryAttempt = 0, maxRetries)
    }

    private def retryWithBackoffImpl(
        onError: OnError[F],
        delay: Duration,
        retryAttempt: Int,
        maxRetries: Int
    )(implicit temporal: Temporal[F]): F[A] = {
      fa.handleErrorWith { error =>
        if (retryAttempt < maxRetries)
          onError(error, delay, retryAttempt + 1, maxRetries) *> temporal.sleep(
            delay
          ) >> retryWithBackoffImpl(onError, delay * 2, retryAttempt + 1, maxRetries)
        else temporal.raiseError(error)
      }
    }
  }

}
