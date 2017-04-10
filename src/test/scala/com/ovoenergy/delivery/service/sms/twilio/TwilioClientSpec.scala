package com.ovoenergy.delivery.service.sms.twilio

import java.io.ByteArrayOutputStream
import java.net.URLDecoder

import cats.syntax.either._
import com.ovoenergy.comms.model.Channel.SMS
import com.ovoenergy.comms.model.Gateway.Twilio
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.{ArbGenerator, Retry}
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import okhttp3._
import okio.Okio
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.Try

// Implicits
import org.scalacheck.Shapeless._
import org.scalacheck._
import org.scalatest.prop._
import org.scalatest.{Failed => _, _}

class TwilioClientSpec extends FlatSpec with Matchers with ArbGenerator with EitherValues {

  val composedSMS: ComposedSMS = generate[ComposedSMS]

  val retryConfig = RetryConfig(
    refineV[Positive](1).right.getOrElse(sys.error(s"NOOO")),
    Retry.Backoff.constantDelay(FiniteDuration(1, "second"))
  )

  val accountSid = generate[String]
  val authToken  = generate[String]
  val serviceSid = generate[String]
  val url        = "https://test.com"

  private def getFileString(file: String) = {
    Source
      .fromFile(file)
      .mkString
  }

  val validResponse           = getFileString("src/test/resources/SuccessfulTwilioResponse.json")
  val unauthenticatedResponse = getFileString("src/test/resources/FailedTwilioResponse.json")
  val badRequestResponse      = getFileString("src/test/resources/BadRequestTwilioResponse.json")

  def httpClient(responseBody: String, responseStatusCode: Int, assertions: Request => Unit) = (request: Request) => {

    val out    = new ByteArrayOutputStream
    val buffer = Okio.buffer(Okio.sink(out))
    request.body().writeTo(buffer)
    buffer.flush()
    assertions(request)

    Try[Response] {
      new Response.Builder()
        .protocol(Protocol.HTTP_1_1)
        .request(request)
        .code(responseStatusCode)
        .body(ResponseBody.create(MediaType.parse("UTF-8"), responseBody))
        .build()
    }
  }

  it should "Handle valid response from Twilio API" in {
    val config = TwilioClient.Config(
      accountSid,
      authToken,
      serviceSid,
      url,
      retryConfig,
      httpClient(validResponse, 200, _ => ())
    )

    val composedNoText = composedSMS
    val result         = TwilioClient.send(config)(composedSMS)

    result shouldBe Right(GatewayComm(Twilio, "1234567890", SMS))
  }

  it should "Handle 401 Not Authenticated responses" in {
    val config = TwilioClient.Config(
      accountSid,
      authToken,
      serviceSid,
      url,
      retryConfig,
      httpClient(unauthenticatedResponse, 401, _ => ())
    )

    val composedNoText = composedSMS.copy(textBody = "Hi!")
    val result         = TwilioClient.send(config)(composedSMS)

    result shouldBe Left(APIGatewayAuthenticationError(ErrorCode.SMSGatewayError))
  }

  it should "Handle Bad request responses" in {
    val config = TwilioClient.Config(
      accountSid,
      authToken,
      serviceSid,
      url,
      retryConfig,
      httpClient(badRequestResponse, 400, _ => ())
    )

    val composedNoText = composedSMS.copy(textBody = "Hi!")
    val result         = TwilioClient.send(config)(composedSMS)

    result shouldBe Left(APIGatewayBadRequest(ErrorCode.SMSGatewayError))
  }
}
