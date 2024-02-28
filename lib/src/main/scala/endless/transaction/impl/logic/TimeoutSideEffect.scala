package endless.transaction.impl.logic

import cats.Applicative
import cats.effect.kernel.syntax.spawn.*
import cats.effect.kernel.{Fiber, Ref, Temporal}
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import endless.transaction.Transaction.Status

import scala.concurrent.duration.FiniteDuration

private[transaction] trait TimeoutSideEffect[F[_]] {
  def scheduleTimeoutAccordingTo[R](status: Status[R]): F[Unit]
}

private[transaction] object TimeoutSideEffect {
  private type UnitFiber[F[_]] = Fiber[F, Throwable, Unit]

  def apply[F[_]: Temporal](
      timeout: Option[FiniteDuration],
      triggerTimeout: F[Unit]
  ): F[TimeoutSideEffect[F]] =
    for {
      neverTimeout <- Temporal[F].never[Unit].start
      timeoutFiberRef <- Ref.of(neverTimeout)
      timeoutSideEffect <- new FiberBasedTimeoutSideEffect[F](
        timeoutFiberRef,
        timeout,
        triggerTimeout
      )
        .pure[F]
    } yield timeoutSideEffect

  private class FiberBasedTimeoutSideEffect[F[_]](
      timeoutFiberRef: Ref[F, UnitFiber[F]],
      timeout: Option[FiniteDuration],
      triggerTimeout: F[Unit]
  )(implicit temporal: Temporal[F])
      extends TimeoutSideEffect[F] {
    import temporal.*

    def scheduleTimeoutAccordingTo[R](status: Status[R]): F[Unit] = timeout match {
      case Some(duration) =>
        status match {
          case Status.Preparing =>
            (timeoutFiberRef.access >>= { case (fiber, set) =>
              fiber.cancel >> sleepThenTrigger(duration).start >>= set
            }).void
          case _ =>
            (timeoutFiberRef.access >>= { case (fiber, set) =>
              fiber.cancel >> never[Unit].start >>= set
            }).void
        }
      case None => Applicative[F].unit
    }

    private def sleepThenTrigger(duration: FiniteDuration) = sleep(duration) >> triggerTimeout
  }
}
