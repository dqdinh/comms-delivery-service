package com.ovoenergy.delivery.service.email

import java.time.{Clock, Instant}

import com.ovoenergy.comms.model.Arbitraries
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.ovoenergy.delivery.service.validation.BlackWhiteList
import org.scalacheck.Arbitrary
import org.scalatest._
import org.scalatest.prop._

import scala.language.postfixOps

class IssueEmailSpec
    extends FlatSpec
    with Matchers
    with Arbitraries
    with ArbGenerator
    with GeneratorDrivenPropertyChecks {

  private implicit val clock = Clock.systemUTC()

  implicit val arbDeliveryError: Arbitrary[DeliveryError] = Arbitrary {
    genNonEmptyString.flatMap(DuplicateDeliveryError.apply)
  }

  private val gatewayComm   = generate[GatewayComm]
  private val composedEmail = generate[ComposedEmailV4]
  private val deliveryError = generate[DeliveryError]

  private val blackWhiteListOK      = (_: String) => BlackWhiteList.OK
  private val successfullySendEmail = (_: ComposedEmailV4) => Right(gatewayComm)
  private val notExpired            = (_: Option[Instant]) => false

  behavior of "EmailDeliveryProcess"

  it should "Handle Successfully sent emails" in {
    val result = IssueEmail.issue(blackWhiteListOK, notExpired, successfullySendEmail)(composedEmail)
    result shouldBe Right(gatewayComm)
  }

  it should "Handle emails which have failed to send, generating appropriate error code in failed event" in {
    val failToSendEmail = (_: ComposedEmailV4) => Left(deliveryError)

    val result = IssueEmail.issue(blackWhiteListOK, notExpired, failToSendEmail)(composedEmail)
    result shouldBe Left(deliveryError)
  }

  it should "not send an email if the recipient address is blacklisted" in {
    val blacklisted = (_: String) => BlackWhiteList.Blacklisted

    val result = IssueEmail.issue(blacklisted, notExpired, successfullySendEmail)(composedEmail)
    result shouldBe Left(EmailAddressBlacklisted(composedEmail.recipient))

  }

  it should "not send an email if the comm has expired" in {
    val expired = (_: Option[Instant]) => true

    val result = IssueEmail.issue(blackWhiteListOK, expired, successfullySendEmail)(composedEmail)
    result shouldBe Left(Expired)
  }

}
