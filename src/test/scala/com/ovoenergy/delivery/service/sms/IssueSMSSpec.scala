package com.ovoenergy.delivery.service.sms

import java.time.Clock

import com.ovoenergy.comms.model.sms.ComposedSMSV2
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.ovoenergy.delivery.service.validation.BlackWhiteList
import org.scalacheck.Shapeless._
import org.scalatest.{FlatSpec, Matchers}

class IssueSMSSpec extends FlatSpec with Matchers with ArbGenerator {

  private implicit val clock = Clock.systemUTC()

  private val gatewayComm   = generate[GatewayComm]
  private val composedSMS   = generate[ComposedSMSV2]
  private val deliveryError = generate[DeliveryError]

  private val blackWhiteListOK    = (_: String) => BlackWhiteList.OK
  private val successfullySendSMS = (_: ComposedSMSV2) => Right(gatewayComm)
  private val notExpired          = (_: Option[String]) => false

  behavior of "IssueSMS"

  it should "Handle Successfully sent SMS" in {
    val result = IssueSMS.issue(blackWhiteListOK, notExpired, successfullySendSMS)(composedSMS)
    result shouldBe Right(gatewayComm)
  }

  it should "Handle SMS which has failed to send, generating appropriate delivery error" in {
    val failToSendSMS = (_: ComposedSMSV2) => Left(deliveryError)

    val result = IssueSMS.issue(blackWhiteListOK, notExpired, failToSendSMS)(composedSMS)
    result shouldBe Left(deliveryError)
  }

  it should "not send an SMS if the recipient address is blacklisted" in {
    val blacklisted = (_: String) => BlackWhiteList.Blacklisted

    val result = IssueSMS.issue(blacklisted, notExpired, successfullySendSMS)(composedSMS)
    result shouldBe Left(EmailAddressBlacklisted(composedSMS.recipient))
  }

  it should "not send an SMS if the recipient address is not on the whitelist" in {
    val notWhiteListed = (_: String) => BlackWhiteList.NotWhitelisted

    val result = IssueSMS.issue(notWhiteListed, notExpired, successfullySendSMS)(composedSMS)
    result shouldBe Left(EmailAddressNotWhitelisted(composedSMS.recipient))
  }

  it should "not send an SMS if the comm has expired" in {
    val expired = (_: Option[String]) => true

    val result = IssueSMS.issue(blackWhiteListOK, expired, successfullySendSMS)(composedSMS)
    result shouldBe Left(Expired)
  }
}
