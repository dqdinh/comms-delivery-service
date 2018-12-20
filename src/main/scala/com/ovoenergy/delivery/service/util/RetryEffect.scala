package com.ovoenergy.delivery.service.util

import fs2._
import cats.effect._
import cats.implicits._

import com.ovoenergy.delivery.service.util.RetryEffect.Strategy

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

object RetryEffect {

  type Strategy = FiniteDuration => FiniteDuration

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
}

class RetryEffect(delay: FiniteDuration, maxRetries: Int, strategy: Strategy) {
  def apply[F[_]: Concurrent: Timer, A](fa: F[A], isRetriable: Throwable => Boolean = NonFatal.apply): F[A] =
    Stream.retry(fa, delay, strategy, maxRetries, isRetriable).compile.lastOrError
}
