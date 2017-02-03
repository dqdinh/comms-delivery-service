package com.ovoenergy.delivery.service.util

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._

object Retry {

  case class Failed[A](attemptsMade: Int, finalFailure: A)

  case class Succeeded[A](result: A, attempts: Int)

  /**
    * @param attempts The total number of attempts to make, including both the first attempt and any retries.
    * @param backoff Sleep between attempts. The number of attempts made so far is passed as an argument.
    */
  case class RetryConfig(attempts: Int Refined Positive, backoff: Int => Unit)

  /**
    * Attempt to perform an operation up to a given number of times, then give up.
    *
    * @param onFailure A hook that is called after each failure. Useful for logging.
    * @param f The operation to perform.
    */
  def retry[A, B](config: RetryConfig, onFailure: A => Unit)(f: () => Either[A, B]): Either[Failed[A], Succeeded[B]] = {
    @tailrec
    def rec(attempt: Int): Either[Failed[A], Succeeded[B]] = {
      f() match {
        case Right(result) => Right(Succeeded(result, attempt))
        case Left(failure) =>
          onFailure(failure)
          if (attempt == config.attempts.value) {
            Left(Failed(attempt, failure))
          } else {
            config.backoff(attempt)
            rec(attempt + 1)
          }
      }
    }

    rec(1)
  }

  object Backoff {

    val retryImmediately = (_: Int) => ()

    def constantDelay(interval: FiniteDuration) = (_: Int) => Thread.sleep(interval.toMillis)

    def exponential(initialInterval: FiniteDuration, exponent: Double) = (attemptsSoFar: Int) => {
      val interval = initialInterval * Math.pow(exponent, attemptsSoFar - 1)
      Thread.sleep(interval.toMillis)
    }

  }

}
