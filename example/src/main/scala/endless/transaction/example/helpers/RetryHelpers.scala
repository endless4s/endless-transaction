package endless.transaction.example.helpers

import cats.effect.kernel.Temporal
import cats.syntax.applicativeError.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import scala.concurrent.duration.*

object RetryHelpers {

  implicit class RetryOps[F[_], A](fa: F[A]) {
    def retryWithBackoff(
        onError: Throwable => F[Unit],
        initialDelay: Duration = 1.second,
        maxRetries: Int = 10
    )(implicit temporal: Temporal[F]): F[A] = {
      fa.handleErrorWith { error =>
        if (maxRetries <= 0) temporal.raiseError(error)
        else
          onError(error) *> temporal
            .sleep(initialDelay) >> fa.retryWithBackoff(onError, initialDelay * 2, maxRetries - 1)

      }
    }
  }

}
