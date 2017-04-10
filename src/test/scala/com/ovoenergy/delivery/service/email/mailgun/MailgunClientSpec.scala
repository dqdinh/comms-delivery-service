package com.ovoenergy.delivery.service.email.mailgun

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.time._
import java.time.format.DateTimeFormatter
import java.util.UUID

import akka.Done
import com.ovoenergy.comms.model.Channel.Email
import com.ovoenergy.comms.model.EmailStatus.Queued
import com.ovoenergy.comms.model.ErrorCode.EmailGatewayError
import com.ovoenergy.comms.model.Gateway.Mailgun
import com.ovoenergy.comms.model.{Metadata, _}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.email._
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient.CustomFormData
import com.ovoenergy.delivery.service.util.{ArbGenerator, Retry}
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import com.sksamuel.avro4s.AvroDoc
import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.generic.auto._
import io.circe.{Decoder, Error}
import okhttp3._
import okio.Okio
import org.scalacheck.Shapeless._
import org.scalacheck._
import org.scalatest.prop._
import org.scalatest.{Failed => _, _}

import scala.util.Try
import scala.util.matching.Regex

class MailgunClientSpec extends FlatSpec with Matchers with ArbGenerator with EitherValues {

  val dateTime = OffsetDateTime.now(ZoneId.of("UTC"))

  implicit val clock                      = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))
  implicit val decoder: Decoder[CommType] = deriveEnumerationDecoder[CommType]

  val mailgunDomain   = "jifjfofjosdfjdoisj"
  val mailgunApiKey   = "dfsfsfdsfdsfs"
  val mailgunHost     = "https://api.mailgun.net"
  val gatewayId       = "<20161117104927.21714.32140.310532EA@sandbox98d59d0a8d0a4af588f2bb683a4a57cc.mailgun.org>"
  val successResponse = "{\n  \"id\": \"" + gatewayId + "\",\n  \"message\": \"Queued. Thank you.\"\n}"

  val progressed    = generate[EmailProgressed]
  val failed        = generate[Failed]
  val composedEmail = generate[ComposedEmail]
  val uUID          = generate[UUID]
  val emailSentRes  = generate[Done]
  val deliveryError = generate[DeliveryError]

  behavior of "The Mailgun Client"

  it should "Send correct request to Mailgun API when only HTML present" in {
    val composedNoText = composedEmail.copy(textBody = None)
    val okResponse = (request: Request) => {
      request.header("Authorization") shouldBe "Basic YXBpOmRmc2ZzZmRzZmRzZnM="
      request.url.toString shouldBe s"https://api.mailgun.net/v3/$mailgunDomain/messages"

      val out    = new ByteArrayOutputStream
      val buffer = Okio.buffer(Okio.sink(out))
      request.body().writeTo(buffer)
      buffer.flush()
      assertFormData(out, false)

      Try[Response] {
        new Response.Builder()
          .protocol(Protocol.HTTP_1_1)
          .request(request)
          .code(200)
          .body(ResponseBody.create(MediaType.parse("UTF-8"), successResponse))
          .build()
      }
    }

    val config = buildConfig(okResponse)
    MailgunClient.sendEmail(config)(composedNoText) match {
      case Right(gatewayComm) =>
        gatewayComm.id shouldBe gatewayId
        gatewayComm.gateway shouldBe Gateway.Mailgun
        gatewayComm.channel shouldBe Email
      case Left(_) => { println("FAILED!"); fail() }
    }
  }
  it should "Send correct request to Mailgun API when both text and HTML present" in {
    val textBody              = Some("textBody")
    val composedEmailWithText = composedEmail.copy(textBody = textBody)
    val okResponse = (request: Request) => {
      request.header("Authorization") shouldBe "Basic YXBpOmRmc2ZzZmRzZmRzZnM="
      request.url.toString shouldBe s"https://api.mailgun.net/v3/$mailgunDomain/messages"

      val out    = new ByteArrayOutputStream
      val buffer = Okio.buffer(Okio.sink(out))
      request.body().writeTo(buffer)
      buffer.flush()
      assertFormData(out, true)

      Try[Response] {
        new Response.Builder()
          .protocol(Protocol.HTTP_1_1)
          .request(request)
          .code(200)
          .body(ResponseBody.create(MediaType.parse("UTF-8"), successResponse))
          .build()
      }
    }
    val config = buildConfig(okResponse)
    MailgunClient.sendEmail(config)(composedEmailWithText)
  }

  it should "Generate correct failure when an exception is thrown in the http client" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        throw new IllegalStateException("I am blown up")
      }
    }
    val config = buildConfig(badResponse)
    MailgunClient.sendEmail(config)(composedEmail) match {
      case Right(_) => fail()
      case Left(f)  => f shouldBe ExceptionOccurred(EmailGatewayError)
    }
  }

  it should "Generate correct failure for a 'bad request' response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder()
          .protocol(Protocol.HTTP_1_1)
          .request(request)
          .code(400)
          .body(ResponseBody.create(MediaType.parse("UTF-8"), """{"message": "Some error message"}"""))
          .build()
      }
    }
    val config = buildConfig(badResponse)
    val result = MailgunClient.sendEmail(config)(composedEmail)

    result shouldBe Left(APIGatewayBadRequest(EmailGatewayError))
  }

  it should "Generate correct failure for a '5xx' responses from Mailgun API" in {
    for (responseCode <- 500 to 511) {
      val badResponse = (request: Request) => {
        Try[Response] {
          new Response.Builder()
            .protocol(Protocol.HTTP_1_1)
            .request(request)
            .code(responseCode)
            .body(ResponseBody.create(MediaType.parse("UTF-8"), ""))
            .build()
        }
      }
      val config = buildConfig(badResponse)
      val result = MailgunClient.sendEmail(config)(composedEmail)

      result shouldBe Left(APIGatewayInternalServerError(EmailGatewayError))
    }
  }

  it should "Generate correct failure for a 'authorization error' response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder()
          .protocol(Protocol.HTTP_1_1)
          .request(request)
          .code(401)
          .body(ResponseBody.create(MediaType.parse("UTF-8"), ""))
          .build()
      }
    }
    val config = buildConfig(badResponse)
    val result = MailgunClient.sendEmail(config)(composedEmail)

    result shouldBe Left(APIGatewayAuthenticationError(EmailGatewayError))
  }

  it should "Generate correct failure for any other response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder()
          .protocol(Protocol.HTTP_1_1)
          .request(request)
          .code(422)
          .body(ResponseBody.create(MediaType.parse("UTF-8"), ""))
          .build()
      }
    }
    val config = buildConfig(badResponse)
    val result = MailgunClient.sendEmail(config)(composedEmail)

    result shouldBe Left(APIGatewayUnspecifiedError(EmailGatewayError))
  }

  private def buildConfig(httpClient: Request => Try[Response]) = MailgunClient.Configuration(
    mailgunHost,
    mailgunDomain,
    mailgunApiKey,
    httpClient,
    RetryConfig(refineMV[Positive](1), Retry.Backoff.retryImmediately)
  )

  private def assertMetadata(metadata: Metadata): Unit = {
    metadata.customerId shouldBe composedEmail.metadata.customerId
    metadata.canary shouldBe composedEmail.metadata.canary
    metadata.source shouldBe "delivery-service"
    metadata.sourceMetadata.get shouldBe composedEmail.metadata
    metadata.traceToken shouldBe composedEmail.metadata.traceToken
    metadata.createdAt shouldBe dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    metadata.friendlyDescription shouldBe composedEmail.metadata.friendlyDescription
  }

  private def assertFormData(out: ByteArrayOutputStream, textIncluded: Boolean) = {
    val formData = out.toString("UTF-8").split("&").map(formEntry => URLDecoder.decode(formEntry, "UTF-8"))

    formData should contain(s"from=${composedEmail.sender}")
    formData should contain(s"to=${composedEmail.recipient}")
    formData should contain(s"subject=${composedEmail.subject}")
    formData should contain(s"html=${composedEmail.htmlBody}")

    if (textIncluded) formData should contain(s"text=textBody")

    val regex: Regex = "v:custom=(.*)".r

    val data = formData
      .collectFirst {
        case regex(customJson) => decode[CustomFormData](customJson)
      }
      .getOrElse(fail())

    data match {
      case Left(error) => fail
      case Right(customJson) => {
        val commManifestRes = customJson.commManifest

        customJson.createdAt shouldBe dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        customJson.customerId shouldBe composedEmail.metadata.customerId
        customJson.traceToken shouldBe composedEmail.metadata.traceToken
        customJson.canary shouldBe composedEmail.metadata.canary
        customJson.internalTraceToken shouldBe composedEmail.internalMetadata.internalTraceToken
        customJson.triggerSource shouldBe composedEmail.metadata.triggerSource
        commManifestRes.commType shouldBe composedEmail.metadata.commManifest.commType
        commManifestRes.name shouldBe composedEmail.metadata.commManifest.name
        commManifestRes.version shouldBe composedEmail.metadata.commManifest.version
      }
    }
  }
}
