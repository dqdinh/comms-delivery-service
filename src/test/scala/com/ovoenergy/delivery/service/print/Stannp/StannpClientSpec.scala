package com.ovoenergy.delivery.service.print.Stannp

import java.io.ByteArrayOutputStream
import java.time.{Clock, OffsetDateTime, ZoneId}

import scala.concurrent.duration._
import akka.Done
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.{ComposedPrint, ComposedPrintV2}
import com.ovoenergy.delivery.config.{ConstantDelayRetry, StannpConfig}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.print.stannp.StannpClient
import com.ovoenergy.delivery.service.util.ArbGenerator
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.circe
import io.circe.parser._
import okhttp3._
import okio.{Buffer, BufferedSink, Okio}
import org.mockserver.client.server.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.{EitherValues, FlatSpec, Matchers}
import org.scalacheck.Shapeless._
import org.scalatest.{Failed => _, _}

import scala.collection.immutable
import scala.util.Try

class StannpClientSpec extends FlatSpec with Matchers with Arbitraries with ArbGenerator with EitherValues {
  val dateTime = OffsetDateTime.now(ZoneId.of("UTC"))

  val url      = "https://dash.stannp.com"
  val test     = true
  val country  = "GB"
  val apiKey   = ""
  val password = ""
  val retry    = ConstantDelayRetry(refineV[Positive](1).right.get, 1.second)

  implicit val clock        = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))
  implicit val stannpConfig = StannpConfig(url, apiKey, password, country, test, retry)

  val composedPrint                                             = generate[ComposedPrintV2]
  val printSentRes                                              = generate[Done]
  val deliveryError                                             = generate[DeliveryError]
  val pdfDocument                                               = generate[PdfDocument]
  val metadata                                                  = generate[MetadataV3]
  val canaryMetadata                                            = metadata.copy(canary = true)
  val productionMetadata                                        = metadata.copy(canary = false)
  val httpClient: (Request => Unit) => Request => Try[Response] = httpClient(200, successResponse)

  val successResponse            = "{\"success\":true,\"data\":{\"id\":\"1234\"}}"
  val authorisationErrorResponse = "{\"success\":false,\"error\":\"You do not have authorisation\"}"

  val assertions: Request => Unit = (request: Request) => {
    request.header("Authorization") shouldBe "Basic Og=="
    request.url.toString shouldBe s"$url/api/v1/letters/post"
  }

  def httpClient(responseCode: Int, responseBody: String)(assertions: Request => Unit) = (request: Request) => {
    val out    = new ByteArrayOutputStream
    val buffer = Okio.buffer(Okio.sink(out))
    request.body().writeTo(buffer)
    buffer.flush()

    assertions(request)

    Try[Response] {
      new Response.Builder()
        .protocol(Protocol.HTTP_1_1)
        .request(request)
        .code(responseCode)
        .body(ResponseBody.create(MediaType.parse("UTF-8"), responseBody))
        .build()
    }
  }

  def testParameterValue(body: String): String = {
    body
      .split("Content-Disposition")
      .map(_.split("\n"))
      .find(_.toList.head.contains("""name="test""""))
      .flatMap(_.drop(3).headOption)
      .getOrElse("")
      .trim
  }

  def canaryAssertions(expected: Boolean) = (request: Request) => {
    val buffer = new Buffer()
    val body   = request.body().writeTo(buffer)
    testParameterValue(buffer.readUtf8) shouldBe expected.toString
    ()
  }

  it should "send correct request to Stannp API " in {
    val result = StannpClient.send(httpClient(200, successResponse)(assertions)).apply(pdfDocument, composedPrint)
    result shouldBe Right(GatewayComm(gateway = Stannp, id = "1234", channel = Print))
  }

  it should "generate correct failure when wrong authentication is sent" in {
    val result =
      StannpClient.send(httpClient(401, authorisationErrorResponse)(assertions)).apply(pdfDocument, composedPrint)
    result shouldBe Left(APIGatewayAuthenticationError(PrintGatewayError))
  }

  it should "generate correct failure when Stannp has internal error" in {
    val result =
      StannpClient.send(httpClient(500, authorisationErrorResponse)(assertions)).apply(pdfDocument, composedPrint)
    result shouldBe Left(APIGatewayInternalServerError(PrintGatewayError))
  }

  it should "generate correct failure when unspecified error received" in {
    val result =
      StannpClient.send(httpClient(404, authorisationErrorResponse)(assertions)).apply(pdfDocument, composedPrint)
    result shouldBe Left(StannpConnectionError(PrintGatewayError))
  }

  it should "send non-test request when in PRD environment and the comm is not a canary" in {
    val sendPrint = StannpClient.send(httpClient(canaryAssertions(false)))(stannpConfig.copy(test = false), clock)
    sendPrint(pdfDocument, composedPrint.copy(metadata = productionMetadata))
  }

  it should "send test request when in PRD environment and the comm is a canary" in {
    val sendPrint = StannpClient.send(httpClient(canaryAssertions(true)))(stannpConfig.copy(test = false), clock)
    sendPrint(pdfDocument, composedPrint.copy(metadata = canaryMetadata))
  }

  it should "send test request when not in PRD environment" in {
    val sendPrint = StannpClient.send(httpClient(canaryAssertions(true)))(stannpConfig.copy(test = true), clock)
    sendPrint(pdfDocument, composedPrint)
  }
}
