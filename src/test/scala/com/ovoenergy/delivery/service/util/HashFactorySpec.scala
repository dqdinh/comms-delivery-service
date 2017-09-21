package com.ovoenergy.delivery.service.util

import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.comms.model.sms.ComposedSMSV2
import org.scalatest.{FlatSpec, Matchers}
import org.scalacheck.Shapeless._

class HashFactorySpec extends FlatSpec with Matchers with ArbGenerator {

  it should "get correct hash code for emails" in {

    val email = generate[ComposedEmailV2]

    val hashFactory = new HashFactory
    val commRecord  = hashFactory.getHashedCommBody(email)

    for (test <- 1 to 10) {
      commRecord shouldBe hashFactory.getHashedCommBody(email)
    }
  }

  it should "get correct hash code for sms" in {

    val sms = generate[ComposedSMSV2]

    val hashFactory = new HashFactory
    val commRecord  = hashFactory.getHashedCommBody(sms)

    for (test <- 1 to 10) {
      commRecord shouldBe hashFactory.getHashedCommBody(sms)
    }
  }

}
