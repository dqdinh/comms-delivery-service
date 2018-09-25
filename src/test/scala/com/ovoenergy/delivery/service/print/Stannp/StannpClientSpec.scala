package com.ovoenergy.delivery.service.print.Stannp

import java.io.ByteArrayOutputStream
import java.time.{OffsetDateTime, ZoneId}

import cats.effect.{IO, Sync}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.delivery.config.{ConstantDelayRetry, StannpConfig}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.print.stannp.StannpClient
import com.ovoenergy.delivery.service.util.ArbGenerator
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import okhttp3._
import okio.{Buffer, Okio}
import org.scalatest.{AsyncFlatSpec, EitherValues, Matchers, Failed => _}
import org.scalacheck.Shapeless._

import scala.concurrent.duration._
import scala.util.Try

class StannpClientSpec extends AsyncFlatSpec with Matchers with Arbitraries with ArbGenerator with EitherValues {
  val dateTime = OffsetDateTime.now(ZoneId.of("UTC"))

  val url      = "https://dash.stannp.com"
  val test     = true
  val country  = "GB"
  val apiKey   = ""
  val password = ""
  val retry    = ConstantDelayRetry(refineV[Positive](1).right.get, 1.second)

  implicit val stannpConfig = StannpConfig(url, apiKey, password, country, test, retry)

  val composedPrint      = generate[ComposedPrintV2]
  val pdfDocument        = generate[Content.Print]
  val metadata           = generate[MetadataV3]
  val canaryMetadata     = metadata.copy(canary = true)
  val productionMetadata = metadata.copy(canary = false)

  val successResponse            = "{\"success\":true,\"data\":{\"id\":\"1234\"}}"
  val authorisationErrorResponse = "{\"success\":false,\"error\":\"You do not have authorisation\"}"

  val assertions: Request => Unit = (request: Request) => {
    request.header("Authorization") shouldBe "Basic Og=="
    request.url.toString shouldBe s"$url/api/v1/letters/post"
  }

  val successfulHttpClient: (Request => Unit) => Request => Try[Response] = httpClient(200, successResponse, _)
  def httpClient(responseCode: Int, responseBody: String, assertions: Request => Unit = assertions) =
    (request: Request) => {
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
    assertions(request)
    val buffer = new Buffer()
    val body   = request.body().writeTo(buffer)
    testParameterValue(buffer.readUtf8) shouldBe expected.toString
    ()
  }

  it should "send correct request to Stannp API " in {
    StannpClient
      .send[IO](httpClient(200, successResponse))
      .apply(pdfDocument, composedPrint)
      .unsafeToFuture()
      .map(_ shouldBe GatewayComm(gateway = Stannp, id = "1234", channel = Print))
  }

  it should "generate correct failure when wrong authentication is sent" in {
    StannpClient
      .send[IO](httpClient(401, authorisationErrorResponse))
      .apply(pdfDocument, composedPrint)
      .unsafeToFuture()
      .failed
      .map { res =>
        val APIGatewayAuthenticationError(code, _) = res
        code shouldBe PrintGatewayError
      }
  }

  it should "generate correct failure when Stannp has internal error" in {
    StannpClient
      .send[IO](httpClient(500, authorisationErrorResponse))
      .apply(pdfDocument, composedPrint)
      .unsafeToFuture()
      .failed
      .map { res =>
        val APIGatewayInternalServerError(code, _) = res
        code shouldBe PrintGatewayError
      }
  }

  it should "generate correct failure when unspecified error received" in {
    StannpClient
      .send[IO](httpClient(404, authorisationErrorResponse))
      .apply(pdfDocument, composedPrint)
      .unsafeToFuture()
      .failed
      .map { res =>
        val StannpConnectionError(code, _) = res
        code shouldBe PrintGatewayError
      }
  }

  it should "send non-test request when in PRD environment and the comm is not a canary" in {
    StannpClient
      .send[IO](successfulHttpClient(canaryAssertions(false)))(stannpConfig.copy(test = false), Sync[IO])
      .apply(pdfDocument, composedPrint.copy(metadata = productionMetadata))
      .unsafeToFuture()
      .map(_ shouldBe a[GatewayComm])
  }

  it should "send test request when in PRD environment and the comm is a canary" in {
    StannpClient
      .send[IO](successfulHttpClient(canaryAssertions(true)))(stannpConfig.copy(test = false), Sync[IO])
      .apply(pdfDocument, composedPrint.copy(metadata = canaryMetadata))
      .unsafeToFuture()
      .map(_ shouldBe a[GatewayComm])
  }

  it should "send test request when not in PRD environment" in {
    StannpClient
      .send[IO](successfulHttpClient(canaryAssertions(true)))(stannpConfig.copy(test = true), Sync[IO])
      .apply(pdfDocument, composedPrint.copy(metadata = canaryMetadata))
      .unsafeToFuture()
      .map(_ shouldBe a[GatewayComm])
  }
}
