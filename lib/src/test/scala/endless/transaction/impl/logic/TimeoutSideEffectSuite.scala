package endless.transaction.impl.logic

import cats.effect.IO.sleep
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import endless.transaction.Transaction.Status

import scala.concurrent.duration.*

class TimeoutSideEffectSuite extends munit.CatsEffectSuite {

  test("triggers timeout after the duration") {
    for {
      timeoutTriggered <- Ref.of[IO, Boolean](false)
      timeoutDuration = 10.seconds
      program = for {
        sideEffect <- TimeoutSideEffect(
          timeout = Some(timeoutDuration),
          triggerTimeout = timeoutTriggered.set(true)
        )
        _ <- sideEffect.scheduleTimeoutAccordingTo(Status.Preparing)
      } yield ()
      control <- TestControl.execute(program)
      _ <- control.tick
      _ <- control.advance(timeoutDuration)
      _ <- control.tick
      _ <- assertIOBoolean(timeoutTriggered.get)
    } yield ()
  }

  test("cancels the previous timeout if invoked again") {
    for {
      timeoutTriggered <- Ref.of[IO, Boolean](false)
      program = for {
        sideEffect <- TimeoutSideEffect(
          timeout = Some(10.seconds),
          triggerTimeout = timeoutTriggered.set(true)
        )
        _ <- sideEffect.scheduleTimeoutAccordingTo(Status.Preparing)
        _ <- sleep(5.seconds)
        _ <- sideEffect.scheduleTimeoutAccordingTo(Status.Preparing)
      } yield ()
      control <- TestControl.execute(program)
      _ <- control.tick
      _ <- control.advance(5.seconds)
      _ <- control.tick
      _ <- control.advance(5.seconds)
      _ <- control.tick
      _ <- assertIOBoolean(timeoutTriggered.get.map(!_))
      _ <- control.advance(5.seconds)
      _ <- control.tick
      _ <- assertIOBoolean(timeoutTriggered.get)
    } yield ()
  }

  test("does not trigger timeout if timeout is not set") {
    for {
      timeoutTriggered <- Ref.of[IO, Boolean](false)
      program = for {
        sideEffect <- TimeoutSideEffect(
          timeout = None,
          triggerTimeout = timeoutTriggered.set(true)
        )
        _ <- sideEffect.scheduleTimeoutAccordingTo(Status.Preparing)
      } yield ()
      control <- TestControl.execute(program)
      _ <- control.tick
      _ <- control.advance(10.seconds)
      _ <- control.tick
      _ <- assertIOBoolean(timeoutTriggered.get.map(!_))
    } yield ()
  }

  test("does not trigger timeout if status is not Preparing") {
    for {
      timeoutTriggered <- Ref.of[IO, Boolean](false)
      program = for {
        sideEffect <- TimeoutSideEffect(
          timeout = Some(10.seconds),
          triggerTimeout = timeoutTriggered.set(true)
        )
        _ <- sideEffect.scheduleTimeoutAccordingTo(Status.Committing)
      } yield ()
      control <- TestControl.execute(program)
      _ <- control.tick
      _ <- control.advance(10.seconds)
      _ <- control.tick
      _ <- assertIOBoolean(timeoutTriggered.get.map(!_))
    } yield ()
  }
}
