package com.ovoenergy.delivery.service.email.mailgun

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.time._

import cats.effect.IO
import cats.implicits._
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.delivery.config
import com.ovoenergy.delivery.config.MailgunAppConfig
import com.ovoenergy.delivery.service.domain
import com.ovoenergy.delivery.service.domain.Content.CustomFormData
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.ArbGenerator
import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import io.circe
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.generic.auto._
import io.circe.Decoder
import okhttp3._
import okio.Okio
import org.scalatest.{Failed => _, _}

import scala.util.Try
import scala.util.matching.Regex
import scala.concurrent.duration._
import org.scalacheck.Shapeless._
import org.scalatest.concurrent.ScalaFutures

class MailgunClientSpec
    extends AsyncFlatSpec
    with Matchers
    with Arbitraries
    with ArbGenerator
    with EitherValues
    with ScalaFutures {

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

  val composedContent = generate[Content.Email]

  behavior of "The Mailgun Client"

  it should "Send correct request to Mailgun API when only HTML present" in {
    val composedNoText = composedContent.copy(textBody = None)
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

    MailgunClient
      .sendEmail[IO](okResponse)
      .apply(composedNoText)
      .unsafeToFuture
      .map(_ shouldBe GatewayComm(gateway = Mailgun, id = gatewayId, channel = Email))
  }

  it should "Send correct request to Mailgun API when both text and HTML present" in {
    val textBody                = Some(Content.TextBody("textBody"))
    val composedContentWithText = composedContent.copy(textBody = textBody)
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

    MailgunClient
      .sendEmail[IO](okResponse)
      .apply(composedContentWithText)
      .unsafeToFuture
      .map(_ shouldBe GatewayComm(gateway = Mailgun, id = gatewayId, channel = Email))
  }

  it should "Generate correct failure when an exception is thrown in the http client" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        throw new IllegalStateException("I am blown up")
      }
    }

    val res = MailgunClient
      .sendEmail[IO](badResponse)
      .apply(composedContent)
      .unsafeToFuture()
      .failed
      .futureValue

    val ExceptionOccurred(code, _) = res

    code shouldBe EmailGatewayError
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

    val res = MailgunClient
      .sendEmail[IO](badResponse)
      .apply(composedContent)
      .unsafeToFuture()
      .failed
      .futureValue

    val APIGatewayBadRequest(code, _) = res
    code shouldBe EmailGatewayError
  }

  it should "Generate correct failure for a '5xx' responses from Mailgun API" in {
    val f: List[IO[Either[Throwable, domain.GatewayComm]]] = (500 to 511).map { responseCode =>
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
      MailgunClient
        .sendEmail[IO](badResponse)
        .apply(composedContent)
        .attempt
    }.toList

//    // TODO: Better way of doing this
    f.sequence
      .unsafeToFuture()
      .map { results: List[Either[Throwable, domain.GatewayComm]] =>
        val apiErrors = results.flatMap {
          case Left(err: APIGatewayInternalServerError) => Some(err)
          case _                                        => None
        }
        apiErrors.size shouldBe f.size
        val distinctErrorCodes = apiErrors.map(_.error).distinct
        distinctErrorCodes.size shouldBe 1
        distinctErrorCodes.head shouldBe EmailGatewayError
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
    MailgunClient
      .sendEmail[IO](badResponse)
      .apply(composedContent)
      .unsafeToFuture()
      .failed
      .map { res =>
        val APIGatewayAuthenticationError(code, _) = res
        code shouldBe EmailGatewayError
      }
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
    MailgunClient
      .sendEmail[IO](badResponse)
      .apply(composedContent)
      .unsafeToFuture()
      .failed
      .map { res =>
        val APIGatewayUnspecifiedError(code, _) = res
        code shouldBe EmailGatewayError
      }
  }

  private def assertFormData(out: ByteArrayOutputStream, textIncluded: Boolean) = {
    val formData = out.toString("UTF-8").split("&").map(formEntry => URLDecoder.decode(formEntry, "UTF-8"))

    formData should contain(s"from=${composedContent.sender.value}")
    formData should contain(s"to=${composedContent.recipient.value}")
    formData should contain(s"subject=${composedContent.subject.value}")
    formData should contain(s"html=${composedContent.htmlBody.value}")

    if (textIncluded) formData should contain(s"text=textBody")

    val regex: Regex = "v:custom=(.*)".r

    val data: Either[circe.Error, CustomFormData] = formData
      .collectFirst {
        case regex(customJson) => decode[CustomFormData](customJson)
      }
      .getOrElse(fail())
    data match {
      case Left(_)                           => fail
      case Right(customJson: CustomFormData) => customJson shouldBe composedContent.customFormData
    }
  }
}
