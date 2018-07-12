package com.ovoenergy.delivery.service.sms

import java.time.{Clock, Instant}

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.ovoenergy.delivery.service.validation.BlackWhiteList
import org.scalacheck.Shapeless._
import org.scalatest.{FlatSpec, Matchers}
import cats.implicits._
import com.ovoenergy.comms.model.{CommType, UnexpectedDeliveryError}
import com.ovoenergy.comms.templates.ErrorsOr
import com.ovoenergy.comms.templates.model.Brand
import com.ovoenergy.comms.templates.model.Brand.Ovo
import com.ovoenergy.comms.templates.model.template.metadata.{TemplateId, TemplateSummary}

class IssueSMSSpec extends FlatSpec with Matchers with ArbGenerator {

  private implicit val clock = Clock.systemUTC()

  private val gatewayComm   = generate[GatewayComm]
  private val composedSMS   = generate[ComposedSMSV4]
  private val deliveryError = generate[DeliveryError]
  private val brand         = generate[Brand]

  private val blackWhiteListOK    = (_: String) => BlackWhiteList.OK
  private val successfullySendSMS = (_: ComposedSMSV4, _: Brand) => Right(gatewayComm)
  private val notExpired          = (_: Option[Instant]) => false

  private val templateMetadataRepo: TemplateId => Option[ErrorsOr[TemplateSummary]] =
    templateId =>
      Some(
        Valid(
          TemplateSummary(
            templateId,
            generate[String],
            generate[CommType],
            brand,
            generate[String]
          )))

  private val templateMetadataNotFound = (_: TemplateId) => None
  private val templateMetadataRetrievalFailure =
    (_: TemplateId) => Some(Invalid(NonEmptyList.of("Dynamo read failed.")))

  behavior of "IssueSMS"

  it should "Handle Successfully sent SMS" in {
    val result = IssueSMS.issue(blackWhiteListOK, notExpired, templateMetadataRepo, successfullySendSMS)(composedSMS)
    result shouldBe Right(gatewayComm)
  }

  it should "Handle SMS which has failed to send, generating appropriate delivery error" in {
    val failToSendSMS = (_: ComposedSMSV4, _: Brand) => Left(deliveryError)

    val result = IssueSMS.issue(blackWhiteListOK, notExpired, templateMetadataRepo, failToSendSMS)(composedSMS)
    result shouldBe Left(deliveryError)
  }

  it should "not send an SMS if the recipient phone number is blacklisted" in {
    val blacklisted = (_: String) => BlackWhiteList.Blacklisted

    val result = IssueSMS.issue(blacklisted, notExpired, templateMetadataRepo, successfullySendSMS)(composedSMS)
    result shouldBe Left(EmailAddressBlacklisted(composedSMS.recipient))
  }

  it should "not send an SMS if the recipient phone number is not on the whitelist" in {
    val notWhiteListed = (_: String) => BlackWhiteList.NotWhitelisted

    val result = IssueSMS.issue(notWhiteListed, notExpired, templateMetadataRepo, successfullySendSMS)(composedSMS)
    result shouldBe Left(EmailAddressNotWhitelisted(composedSMS.recipient))
  }

  it should "not send an SMS if the comm has expired" in {
    val expired = (_: Option[Instant]) => true

    val result = IssueSMS.issue(blackWhiteListOK, expired, templateMetadataRepo, successfullySendSMS)(composedSMS)
    result shouldBe Left(Expired)
  }

  it should "handle if template metadata was not found" in {
    val result =
      IssueSMS.issue(blackWhiteListOK, notExpired, templateMetadataNotFound, successfullySendSMS)(composedSMS)
    result shouldBe Left(TemplateDetailsNotFoundError)
  }

  it should "handle if dynamo read error occures while retriving template metadata" in {
    val result =
      IssueSMS.issue(blackWhiteListOK, notExpired, templateMetadataRetrievalFailure, successfullySendSMS)(composedSMS)
    result shouldBe Left(DynamoError(UnexpectedDeliveryError))
  }
}
