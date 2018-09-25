package com.ovoenergy.delivery.service.sms

import java.time.{Instant}

import cats.effect.IO
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.comms.model.{Arbitraries, CommType, UnexpectedDeliveryError}
import com.ovoenergy.comms.templates.model.Brand
import com.ovoenergy.comms.templates.model.template.metadata.{TemplateId, TemplateSummary}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.persistence.{CommContent, TemplateMetadataRepo}
import com.ovoenergy.delivery.service.sms.twilio.TwilioClient.TwilioData
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.ovoenergy.delivery.service.validation.BlackWhiteList
import org.scalacheck.Arbitrary
import org.scalatest.{AsyncFlatSpec, Matchers}

class IssueSMSSpec extends AsyncFlatSpec with Matchers with Arbitraries with ArbGenerator {

  implicit val arbDeliveryError: Arbitrary[DeliveryError] = Arbitrary {
    genNonEmptyString.flatMap(DuplicateDeliveryError.apply)
  }

  private val gatewayComm   = generate[GatewayComm]
  private val composedSMS   = generate[ComposedSMSV4]
  private val deliveryError = generate[DeliveryError]
  private val brand         = generate[Brand]

  private val blackWhiteListOK    = (_: String) => BlackWhiteList.OK
  private val successfullySendSMS = (_: TwilioData) => IO(gatewayComm)
  private val notExpired          = (_: Option[Instant]) => false

  private val commContent = new CommContent[IO] {
    override def getEmailContent(composedEmailV4: ComposedEmailV4): IO[Content.Email] =
      fail("Incorrect method invoked on CommContent")
    override def getSMSContent(composedSMSV4: ComposedSMSV4): IO[Content.SMS] =
      IO(Content.SMS(Content.TextBody(composedSMSV4.textBody)))
    override def getPrintContent(composedPrintV2: ComposedPrintV2): IO[Content.Print] =
      fail("Incorrect method invoked on CommContent")
  }

  private val templateMetadataRepo = new TemplateMetadataRepo[IO] {
    override def getTemplateMetadata(templateId: TemplateId): IO[TemplateSummary] =
      IO(
        TemplateSummary(
          templateId,
          generate[String],
          generate[CommType],
          brand,
          generate[String]
        ))
  }
  private val templateMetadataNotFound = new TemplateMetadataRepo[IO] {
    override def getTemplateMetadata(templateId: TemplateId): IO[TemplateSummary] =
      IO.raiseError(TemplateDetailsNotFoundError)
  }

  private val templateMetadataRetrievalFailure = new TemplateMetadataRepo[IO] {
    override def getTemplateMetadata(templateId: TemplateId): IO[TemplateSummary] =
      IO.raiseError(DynamoError(UnexpectedDeliveryError, "error"))
  }

  behavior of "IssueSMS"

  it should "Handle Successfully sent SMS" in {
    IssueSMS
      .issue[IO](blackWhiteListOK, notExpired, templateMetadataRepo, commContent, successfullySendSMS)
      .apply(composedSMS)
      .unsafeToFuture()
      .map(_ shouldBe gatewayComm)
  }

  it should "Handle SMS which has failed to send, generating appropriate delivery error" in {
    val failToSendSMS: (TwilioData) => IO[GatewayComm] = (_: TwilioData) => IO.raiseError(deliveryError)
    IssueSMS
      .issue[IO](blackWhiteListOK, notExpired, templateMetadataRepo, commContent, failToSendSMS)
      .apply(composedSMS)
      .unsafeToFuture()
      .failed
      .map(_ shouldBe deliveryError)
  }

  it should "not send an SMS if the recipient phone number is blacklisted" in {
    val blacklisted = (_: String) => BlackWhiteList.Blacklisted

    IssueSMS
      .issue[IO](blacklisted, notExpired, templateMetadataRepo, commContent, successfullySendSMS)
      .apply(composedSMS)
      .unsafeToFuture
      .failed
      .map(_ shouldBe PhoneNumberBlacklisted(composedSMS.recipient))
  }

  it should "not send an SMS if the recipient phone number is not on the whitelist" in {
    val notWhiteListed = (_: String) => BlackWhiteList.NotWhitelisted

    IssueSMS
      .issue(notWhiteListed, notExpired, templateMetadataRepo, commContent, successfullySendSMS)
      .apply(composedSMS)
      .unsafeToFuture()
      .failed
      .map(_ shouldBe PhoneNumberNotWhitelisted(composedSMS.recipient))
  }

  it should "not send an SMS if the comm has expired" in {
    val expired = (_: Option[Instant]) => true

    IssueSMS
      .issue[IO](blackWhiteListOK, expired, templateMetadataRepo, commContent, successfullySendSMS)
      .apply(composedSMS)
      .unsafeToFuture
      .failed
      .map(_ shouldBe Expired)
  }

  it should "handle if template metadata was not found" in {
    IssueSMS
      .issue[IO](blackWhiteListOK, notExpired, templateMetadataNotFound, commContent, successfullySendSMS)
      .apply(composedSMS)
      .unsafeToFuture
      .failed
      .map(_ shouldBe TemplateDetailsNotFoundError)
  }

  it should "handle if dynamo read error occures while retriving template metadata" in {
    IssueSMS
      .issue[IO](blackWhiteListOK, notExpired, templateMetadataRetrievalFailure, commContent, successfullySendSMS)
      .apply(composedSMS)
      .unsafeToFuture
      .failed
      .map { res =>
        val DynamoError(code, _) = res
        code shouldBe UnexpectedDeliveryError
      }
  }
}
