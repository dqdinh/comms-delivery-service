package com.ovoenergy.delivery.service.util

import java.util.concurrent.TimeUnit

import akka.actor.Scheduler

import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, FiniteDuration}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import akka.pattern.after
import com.ovoenergy.delivery.config.{ConstantDelayRetry, ExponentialDelayRetry}
import com.ovoenergy.delivery.service.domain.DynamoError

import scala.concurrent.{ExecutionContext, Future}

object Retry {

  implicit class EitherExtensions[A, B](retryResult: Either[Failed[A], Succeeded[B]]) {
    def flatten: Either[A, B] = {
      retryResult match {
        case Left(Failed(attempts, finalFailure)) => Left(finalFailure)
        case Right(Succeeded(result, attempts))   => Right(result)
      }
    }
  }

  case class Failed[A](attemptsMade: Int, finalFailure: A)

  case class Succeeded[A](result: A, attempts: Int)

  /**
    * @param attempts The total number of attempts to make, including both the first attempt and any retries.
    * @param backoff  Sleep between attempts. The number of attempts made so far is passed as an argument.
    */
  case class RetryConfig(attempts: Int Refined Positive, backoff: Int => FiniteDuration)

  def constantDelay(retry: ConstantDelayRetry) = {
    RetryConfig(retry.attempts, Backoff.constantDelay(retry.interval))
  }

  def exponentialDelay(retry: ExponentialDelayRetry) = {
    RetryConfig(retry.attempts, Backoff.exponential(retry.initialInterval, retry.exponent))
  }

  /**
    * Attempt to perform an operation up to a given number of times, then give up.
    *
    * @param onFailure A hook that is called after each failure. Useful for logging.
    * @param f         The operation to perform.
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
            Thread.sleep(config.backoff(attempt).toMillis)
            rec(attempt + 1)
          }
      }
    }

    rec(1)
  }

  /**
    * Attempt to perform an asynchronous operation up to a given number of times, then give up.
    *
    * Based on https://gist.github.com/viktorklang/9414163
    *
    * @param onFailure A hook that is called after each failure. Useful for logging.
    * @param f         the operation to perform.
    */
  def retryAsync[A](config: RetryConfig, onFailure: Throwable => Unit, attempt: Int = 1)(
      f: () => Future[A])(implicit ec: ExecutionContext, s: Scheduler): Future[A] = {
    f() recoverWith {
      case e if attempt < config.attempts.value =>
        onFailure(e)
        val delay = config.backoff(attempt)
        after(delay, s)(retryAsync(config, onFailure, attempt + 1)(f))
    }
  }

  object Backoff {

    val retryImmediately = (_: Int) => FiniteDuration.apply(0, TimeUnit.MICROSECONDS)

    def constantDelay(interval: FiniteDuration) = (_: Int) => interval

    def exponential(initialInterval: FiniteDuration, exponent: Double) = (attemptsSoFar: Int) => {
      Duration.fromNanos((initialInterval.toNanos * Math.pow(exponent, attemptsSoFar - 1)).toLong)
    }

  }

}
