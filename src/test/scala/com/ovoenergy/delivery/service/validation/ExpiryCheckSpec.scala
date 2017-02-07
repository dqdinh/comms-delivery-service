package com.ovoenergy.delivery.service.validation

import java.time.{Clock, OffsetDateTime, ZoneId}

import org.scalatest.{FlatSpec, Matchers}

class ExpiryCheckSpec extends FlatSpec with Matchers {

  val dateTime       = OffsetDateTime.now()
  implicit val clock = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))

  it should "recognise an expired comm" in {
    ExpiryCheck.isExpired(clock)(Some(dateTime.minusSeconds(1).toString)) shouldBe true
  }

  it should "recognise an unexpired comm" in {
    ExpiryCheck.isExpired(clock)(Some(dateTime.plusSeconds(1).toString)) shouldBe false
  }

  it should "recognise a comm with no expiry" in {
    ExpiryCheck.isExpired(clock)(None) shouldBe false
  }

}
