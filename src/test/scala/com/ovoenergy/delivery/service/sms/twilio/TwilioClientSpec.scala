package com.ovoenergy.delivery.service.sms.twilio

import java.io.ByteArrayOutputStream

import cats.effect.IO
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.templates.model.Brand
import com.ovoenergy.delivery.config.{ConstantDelayRetry, TwilioAppConfig, TwilioServiceSids}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.sms.twilio.TwilioClient.TwilioData
import com.ovoenergy.delivery.service.util.ArbGenerator
import okhttp3._
import okio.Okio
import org.scalacheck.Arbitrary
import org.scalatest.Matchers

import scala.io.Source
import scala.util.Try

import org.scalacheck.Shapeless._
import org.scalatest.{Failed => _, _}

class TwilioClientSpec extends AsyncFlatSpec with Matchers with Arbitraries with ArbGenerator with EitherValues {

  implicit val arbTwilioConfig: Arbitrary[TwilioAppConfig] = Arbitrary {
    TwilioAppConfig(
      generate[String],
      generate[String],
      TwilioServiceSids(
        generate[String],
        generate[String],
        generate[String],
        generate[String],
        generate[String]
      ),
      "https://test.com",
      generate[ConstantDelayRetry]
    )
  }

  val twilioData = generate[TwilioData]
  val brand      = generate[Brand]

  private def getFileString(file: String) = {
    Source
      .fromFile(file)
      .mkString
  }

  val validResponse           = getFileString("src/test/resources/SuccessfulTwilioResponse.json")
  val unauthenticatedResponse = getFileString("src/test/resources/FailedTwilioResponse.json")
  val badRequestResponse      = getFileString("src/test/resources/BadRequestTwilioResponse.json")

  def httpClient(responseBody: String, responseStatusCode: Int) = (request: Request) => {

    val out    = new ByteArrayOutputStream
    val buffer = Okio.buffer(Okio.sink(out))
    request.body().writeTo(buffer)
    buffer.flush()

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
    implicit val twilioConfig = generate[TwilioAppConfig]
    val client                = httpClient(validResponse, 200)
    TwilioClient
      .send[IO](client)
      .apply(twilioData)
      .unsafeToFuture()
      .map(_ shouldBe GatewayComm(Twilio, "1234567890", SMS))
  }

  it should "Handle 401 Not Authenticated responses" in {
    implicit val twilioConfig = generate[TwilioAppConfig]
    val client                = httpClient(unauthenticatedResponse, 401)

    TwilioClient
      .send[IO](client)
      .apply(twilioData)
      .unsafeToFuture()
      .failed
      .map { res =>
        val APIGatewayAuthenticationError(code, _) = res
        code shouldBe SMSGatewayError
      }
  }

  it should "Handle Bad request responses" in {
    implicit val twilioConfig = generate[TwilioAppConfig]
    val client                = httpClient(badRequestResponse, 400)
    TwilioClient
      .send[IO](client)
      .apply(twilioData)
      .unsafeToFuture()
      .failed
      .map { res =>
        val APIGatewayBadRequest(code, _) = res
        code shouldBe SMSGatewayError
      }
  }
}
