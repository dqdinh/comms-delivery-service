package com.ovoenergy.delivery.service.util

import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import org.scalatest._

class RetrySpec extends FlatSpec with Matchers {
  import Retry._

  val onFailure = (_: String) => ()

  it should "succeed if the operation succeeds on the first attempt" in {
    val result = retry(RetryConfig(refineMV[Positive](5), Backoff.retryImmediately), onFailure)(failNtimesThenSucceed(0))
    result should be(Right(Retry.Succeeded("yay", 1)))
  }

  it should "succeed if the operation fails on the first attempt but succeeds on the second" in {
    val result = retry(RetryConfig(refineMV[Positive](5), Backoff.retryImmediately), onFailure)(failNtimesThenSucceed(1))
    result should be(Right(Retry.Succeeded("yay", 2)))
  }

  it should "succeed if the operation succeeds just before we give up" in {
    val result = retry(RetryConfig(refineMV[Positive](5), Backoff.retryImmediately), onFailure)(failNtimesThenSucceed(4))
    result should be(Right(Retry.Succeeded("yay", 5)))
  }

  it should "fail if the operation fails on every attempt" in {
    val result = retry(RetryConfig(refineMV[Positive](5), Backoff.retryImmediately), onFailure)(failNtimesThenSucceed(5))
    result should be(Left(Retry.Failed(5, "oops")))
  }

  it should "work with attempts == 1" in {
    val config = RetryConfig(refineMV[Positive](1), Backoff.retryImmediately)

    val success = retry(config, onFailure)(failNtimesThenSucceed(0))
    success should be(Right(Retry.Succeeded("yay", 1)))

    val failure = retry(config, onFailure)(failNtimesThenSucceed(1))
    failure should be(Left(Retry.Failed(1, "oops")))
  }

  def failNtimesThenSucceed(n: Int): () => Either[String, String] = {
    var counter = 0
    () => {
      if (counter < n) {
        counter = counter + 1
        Left("oops")
      } else
        Right("yay")
    }
  }

}
