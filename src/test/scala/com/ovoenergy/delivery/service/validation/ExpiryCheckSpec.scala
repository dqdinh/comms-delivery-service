package com.ovoenergy.delivery.service.validation

import java.time.{Clock, Instant, ZoneId}

import org.scalatest.{FlatSpec, Matchers}

class ExpiryCheckSpec extends FlatSpec with Matchers {

  val instant        = Instant.now()
  implicit val clock = Clock.fixed(instant, ZoneId.of("UTC"))

  it should "recognise an expired comm" in {
    ExpiryCheck.isExpired(clock)(Some(instant.minusSeconds(1))) shouldBe true
  }

  it should "recognise an unexpired comm" in {
    ExpiryCheck.isExpired(clock)(Some(instant.plusSeconds(1))) shouldBe false
  }

  it should "recognise a comm with no expiry" in {
    ExpiryCheck.isExpired(clock)(None) shouldBe false
  }

}
