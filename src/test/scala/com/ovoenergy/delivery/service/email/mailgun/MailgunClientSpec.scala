package com.ovoenergy.delivery.service.email.mailgun

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.time._
import java.time.format.DateTimeFormatter

import akka.Done
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.delivery.config
import com.ovoenergy.delivery.config.MailgunAppConfig
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient.CustomFormData
import com.ovoenergy.delivery.service.util.{ArbGenerator, Retry}
import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import io.circe
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.generic.auto._
import io.circe.Decoder
import okhttp3._
import okio.Okio
import org.scalacheck.Shapeless._
import org.scalatest.{Failed => _, _}

import scala.util.Try
import scala.util.matching.Regex
import scala.concurrent.duration._
class MailgunClientSpec extends FlatSpec with Matchers with ArbGenerator with EitherValues {

  val dateTime = OffsetDateTime.now(ZoneId.of("UTC"))

  val mailgunDomain = "jifjfofjosdfjdoisj"
  val mailgunApiKey = "dfsfsfdsfdsfs"
  val mailgunHost   = "https://api.mailgun.net"
  val gatewayId     = "<20161117104927.21714.32140.310532EA@sandbox98d59d0a8d0a4af588f2bb683a4a57cc.mailgun.org>"

  implicit val clock                      = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))
  implicit val decoder: Decoder[CommType] = deriveEnumerationDecoder[CommType]
  implicit val mailgunconfig = MailgunAppConfig(mailgunHost,
                                                mailgunApiKey,
                                                mailgunDomain,
                                                config.ConstantDelayRetry(refineV[Positive](1).right.get, 1.second))

  val successResponse = "{\n  \"id\": \"" + gatewayId + "\",\n  \"message\": \"Queued. Thank you.\"\n}"

  val composedEmail = generate[ComposedEmailV3]
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

    val result = MailgunClient.sendEmail(okResponse).apply(composedNoText)
    result shouldBe Right(GatewayComm(gateway = Mailgun, id = gatewayId, channel = Email))
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

    val result = MailgunClient.sendEmail(okResponse).apply(composedEmailWithText)
    result shouldBe Right(GatewayComm(gateway = Mailgun, id = gatewayId, channel = Email))
  }

  it should "Generate correct failure when an exception is thrown in the http client" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        throw new IllegalStateException("I am blown up")
      }
    }

    val result = MailgunClient.sendEmail(badResponse).apply(composedEmail)

    result shouldBe Left(ExceptionOccurred(EmailGatewayError))
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
    val result = MailgunClient.sendEmail(badResponse).apply(composedEmail)

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
      val result = MailgunClient.sendEmail(badResponse).apply(composedEmail)

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
    val result = MailgunClient.sendEmail(badResponse).apply(composedEmail)

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
    val result = MailgunClient.sendEmail(badResponse).apply(composedEmail)

    result shouldBe Left(APIGatewayUnspecifiedError(EmailGatewayError))
  }

  private def assertFormData(out: ByteArrayOutputStream, textIncluded: Boolean) = {
    val formData = out.toString("UTF-8").split("&").map(formEntry => URLDecoder.decode(formEntry, "UTF-8"))

    formData should contain(s"from=${composedEmail.sender}")
    formData should contain(s"to=${composedEmail.recipient}")
    formData should contain(s"subject=${composedEmail.subject}")
    formData should contain(s"html=${composedEmail.htmlBody}")

    if (textIncluded) formData should contain(s"text=textBody")

    val regex: Regex = "v:custom=(.*)".r

    val data: Either[circe.Error, CustomFormData] = formData
      .collectFirst {
        case regex(customJson) => decode[CustomFormData](customJson)
      }
      .getOrElse(fail())

    data match {
      case Left(error) => fail
      case Right(customJson) => {
        val commManifestRes = customJson.commManifest

        customJson.createdAt shouldBe dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        composedEmail.metadata.deliverTo match {
          case Customer(customerId) => customJson.customerId shouldBe Some(customerId)
          case _                    => customJson.customerId shouldBe None
        }
        customJson.traceToken shouldBe composedEmail.metadata.traceToken
        customJson.canary shouldBe composedEmail.metadata.canary
        customJson.internalTraceToken shouldBe composedEmail.internalMetadata.internalTraceToken
        customJson.triggerSource shouldBe composedEmail.metadata.triggerSource
        customJson.friendlyDescription shouldBe composedEmail.metadata.friendlyDescription
        commManifestRes.commType shouldBe composedEmail.metadata.commManifest.commType
        commManifestRes.name shouldBe composedEmail.metadata.commManifest.name
        commManifestRes.version shouldBe composedEmail.metadata.commManifest.version
      }
    }
  }
}
