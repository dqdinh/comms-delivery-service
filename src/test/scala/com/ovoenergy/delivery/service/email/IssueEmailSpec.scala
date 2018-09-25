package com.ovoenergy.delivery.service.email

import java.time.{Instant, ZonedDateTime}

import cats.effect.IO
import com.ovoenergy.comms.model.Arbitraries
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.delivery.service.domain
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.persistence.CommContent
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.ovoenergy.delivery.service.validation.BlackWhiteList
import org.scalacheck.Arbitrary
import org.scalatest._
import org.scalatest.prop._

import scala.language.postfixOps

class IssueEmailSpec
    extends AsyncFlatSpec
    with Matchers
    with Arbitraries
    with ArbGenerator
    with GeneratorDrivenPropertyChecks {

  private implicit val time = IO(ZonedDateTime.now())

  implicit val arbDeliveryError: Arbitrary[DeliveryError] = Arbitrary {
    genNonEmptyString.flatMap(DuplicateDeliveryError.apply)
  }

  private val gatewayComm   = generate[GatewayComm]
  private val composedEmail = generate[ComposedEmailV4]
  private val deliveryError = generate[DeliveryError]

  private val blackWhiteListOK      = (_: String) => BlackWhiteList.OK
  private val successfullySendEmail = (_: Content.Email) => IO(gatewayComm)
  private val notExpired            = (_: Option[Instant]) => false
  private val commContent = new CommContent[IO] {
    override def getEmailContent(composedEmailV4: ComposedEmailV4): IO[Content.Email] =
      Content.Email[IO](composedEmailV4,
                        Content.Subject(composedEmailV4.subject),
                        Content.HtmlBody(composedEmailV4.htmlBody),
                        composedEmailV4.textBody.map(Content.TextBody))
    override def getSMSContent(composedSMSV4: ComposedSMSV4): IO[Content.SMS] =
      fail("Incorrect method invoked on CommContent")
    override def getPrintContent(composedPrintV2: ComposedPrintV2): IO[Content.Print] =
      fail("Incorrect method invoked on CommContent")
  }

  behavior of "EmailDeliveryProcess"

  it should "Handle Successfully sent emails" in
    IssueEmail
      .issue[IO](blackWhiteListOK, notExpired, commContent, successfullySendEmail)
      .apply(composedEmail)
      .unsafeToFuture()
      .map { r =>
        r shouldBe gatewayComm
      }

  it should "Handle emails which have failed to send, generating appropriate error code in failed event" in {
    val failToSendEmail: Content.Email => IO[domain.GatewayComm] =
      (_: Content.Email) => IO.raiseError(deliveryError)
    IssueEmail
      .issue[IO](blackWhiteListOK, notExpired, commContent, failToSendEmail)
      .apply(composedEmail)
      .unsafeToFuture()
      .failed
      .map(_ shouldBe deliveryError)

  }

  it should "not send an email if the recipient address is blacklisted" in {
    val blacklisted = (_: String) => BlackWhiteList.Blacklisted
    IssueEmail
      .issue[IO](blacklisted, notExpired, commContent, successfullySendEmail)
      .apply(composedEmail)
      .unsafeToFuture()
      .failed
      .map(_ shouldBe EmailAddressBlacklisted(composedEmail.recipient))
  }

  it should "not send an email if the comm has expired" in {
    val expired = (_: Option[Instant]) => true
    IssueEmail
      .issue[IO](blackWhiteListOK, expired, commContent, successfullySendEmail)
      .apply(composedEmail)
      .unsafeToFuture()
      .failed
      .map(_ shouldBe Expired)
  }
}
