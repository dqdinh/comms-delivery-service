package com.ovoenergy.delivery.service.util

import cats.syntax.flatMap._
import cats.effect.{Async, IO}
import com.ovoenergy.delivery.service.util.RetryEffect.Strategy
import fs2.Scheduler

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

object RetryEffect {

  type Strategy = FiniteDuration => FiniteDuration

  private val (defaultScheduler, shutdownDefaultScheduler) =
    Scheduler.allocate[IO](corePoolSize = 16, threadPrefix = "retry").unsafeRunSync()

  private val DefaultMaxRetries    = 10
  private val DefaultInitialDelay  = 250.milliseconds
  private val DefaultBackOffFactor = 2.0
  private val DefaultStrategy      = backOffStrategy(DefaultBackOffFactor)

  private def backOffStrategy(factor: Double): Strategy = { fd =>
    fd * factor match {
      case x: FiniteDuration => x
      case _                 => fd
    }
  }

  private def fixedStrategy: Strategy = identity

  def apply(maxRetries: Int = DefaultMaxRetries,
            delay: FiniteDuration = DefaultInitialDelay,
            strategy: Strategy = DefaultStrategy): RetryEffect = new RetryEffect(
    delay,
    maxRetries + 1, // The initial is counted as well
    strategy
  )

  def backOff(maxRetries: Int = DefaultMaxRetries,
              initialDelay: FiniteDuration = DefaultInitialDelay,
              backOffFactor: Double = DefaultBackOffFactor): RetryEffect =
    apply(maxRetries, initialDelay, backOffStrategy(backOffFactor))

  def fixed(maxRetries: Int = DefaultMaxRetries, fixedDelay: FiniteDuration = DefaultInitialDelay): RetryEffect =
    apply(maxRetries, fixedDelay, fixedStrategy)

  override def finalize(): Unit = {
    shutdownDefaultScheduler.unsafeRunTimed(5.seconds)
    super.finalize()
  }
}

class RetryEffect(delay: FiniteDuration, maxRetries: Int, strategy: Strategy) {

  def apply[F[_], A](fa: F[A], isRetriable: Throwable => Boolean = NonFatal.apply)(
      implicit F: Async[F],
      ec: ExecutionContext,
      s: Scheduler = RetryEffect.defaultScheduler): F[A] = {

    s.retry(fa, delay, strategy, maxRetries, isRetriable).compile.last.flatMap {
      case Some(x) => Async[F].pure(x)
      case None    => Async[F].raiseError[A](new NoSuchElementException)
    }
  }

}
