package com.ovoenergy.delivery.service.sms.twilio

import java.io.ByteArrayOutputStream

import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.templates.model.Brand
import com.ovoenergy.delivery.config.{ConstantDelayRetry, TwilioAppConfig, TwilioServiceSids}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.ArbGenerator
import okhttp3._
import okio.{Buffer, Okio}
import org.scalacheck.Arbitrary
import org.scalatest.{FlatSpec, Matchers}

import scala.io.Source
import scala.util.Try

// Implicits
import org.scalatest.{Failed => _, _}

class TwilioClientSpec extends FlatSpec with Matchers with ArbGenerator with EitherValues {

  val composedSMS = generate[ComposedSMSV4]
  val brand       = generate[Brand]
  implicit val arbTwilioConfig = Arbitrary {
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

  implicit val twilioConfig = generate[TwilioAppConfig]

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
    val sid = TwilioClient.serviceSid(twilioConfig.serviceSids, brand)

    def assertSid(request: Request) = {
      val buffer = new Buffer()
      request.body().writeTo(buffer)
      buffer.readUtf8() should include(sid)
      ()
    }

    val client = httpClient(validResponse, 200, assertSid)
    val result = TwilioClient.send(client)(twilioConfig).apply(composedSMS, brand)

    result shouldBe Right(GatewayComm(Twilio, "1234567890", SMS))
  }

  it should "Handle 401 Not Authenticated responses" in {
    val client = httpClient(unauthenticatedResponse, 401, _ => ())
    val result = TwilioClient.send(client).apply(composedSMS, brand)

    result shouldBe Left(APIGatewayAuthenticationError(SMSGatewayError))
  }

  it should "Handle Bad request responses" in {
    val client = httpClient(badRequestResponse, 400, _ => ())
    val result = TwilioClient.send(client).apply(composedSMS, brand)
    result shouldBe Left(APIGatewayBadRequest(SMSGatewayError))
  }
}
