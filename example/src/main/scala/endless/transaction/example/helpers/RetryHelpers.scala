package endless.transaction.example.helpers

import cats.data.EitherT
import cats.effect.kernel.Temporal
import cats.syntax.applicativeError.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*

import scala.concurrent.duration.*

object RetryHelpers {
  final case class RetryParameters(initialDelay: Duration = 1.second, maxRetries: Int = 10)

  implicit class RetryOps[F[_], A](fa: F[A]) {
    def onErrorRetryWithBackoff(
        onError: Throwable => F[Unit]
    )(implicit parameters: RetryParameters, temporal: Temporal[F]): F[A] = fa.handleErrorWith {
      error =>
        if (parameters.maxRetries <= 0) temporal.raiseError(error)
        else
          onError(error) *> temporal
            .sleep(parameters.initialDelay) >> fa.onErrorRetryWithBackoff(onError)(
            RetryParameters(parameters.initialDelay * 2, parameters.maxRetries - 1),
            temporal
          )
    }
  }

  implicit class EitherRetryOps[F[_], A, B](fab: F[Either[A, B]]) {
    def onLeftRetryWithBackoff(
        pf: PartialFunction[A, F[Unit]]
    )(parameters: RetryParameters)(implicit temporal: Temporal[F]): F[Either[A, B]] = if (
      parameters.maxRetries <= 0
    ) fab
    else
      EitherT(fab)
        .leftFlatMap(a =>
          if (pf.isDefinedAt(a)) {
            EitherT {
              pf(a) *> temporal.sleep(parameters.initialDelay) >>
                fab.onLeftRetryWithBackoff(pf)(
                  RetryParameters(parameters.initialDelay * 2, parameters.maxRetries - 1)
                )
            }
          } else EitherT.leftT[F, B](a)
        )
        .value
  }

}
