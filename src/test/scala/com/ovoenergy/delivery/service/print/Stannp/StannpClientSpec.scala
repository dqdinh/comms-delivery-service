package com.ovoenergy.delivery.service.print.Stannp

import java.io.ByteArrayOutputStream
import java.time.{Clock, OffsetDateTime, ZoneId}

import scala.concurrent.duration._
import akka.Done
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.config.{ConstantDelayRetry, StannpConfig}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.print.stannp.StannpClient
import com.ovoenergy.delivery.service.util.ArbGenerator
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import okhttp3._
import okio.Okio
import org.scalatest.{EitherValues, FlatSpec, Matchers}
import org.scalacheck.Shapeless._
import org.scalatest.{Failed => _, _}

import scala.util.Try

class StannpClientSpec extends FlatSpec with Matchers with ArbGenerator with EitherValues {
  val dateTime = OffsetDateTime.now(ZoneId.of("UTC"))

  val url      = "https://dash.stannp.com"
  val test     = "true"
  val country  = "GB"
  val apiKey   = ""
  val password = ""
  val retry    = ConstantDelayRetry(refineV[Positive](1).right.get, 1.second)

  implicit val clock        = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))
  implicit val stannpConfig = StannpConfig(url, apiKey, password, country, test, retry)

  val composedPrint = generate[ComposedPrint]
  val printSentRes  = generate[Done]
  val deliveryError = generate[DeliveryError]
  val pdfDocument   = generate[PdfDocument]

  val successResponse            = "{\"success\":true,\"data\":{\"id\":\"1234\"}}"
  val authorisationErrorResponse = "{\"success\":false,\"error\":\"You do not have authorisation\"}"

  val assertions: Request => Unit = (request: Request) => {
    request.header("Authorization") shouldBe "Basic Og=="
    request.url.toString shouldBe s"$url/api/v1/letters/post"
  }

  def httpClient(responseCode: Int, responseBody: String) = (request: Request) => {
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

  it should "send correct request to Stannp API " in {
    val result = StannpClient.send(httpClient(200, successResponse)).apply(pdfDocument, composedPrint)
    result shouldBe Right(GatewayComm(gateway = Stannp, id = "1234", channel = Print))
  }

  it should "generate correct failure when wrong authentication is sent" in {
    val result = StannpClient.send(httpClient(401, authorisationErrorResponse)).apply(pdfDocument, composedPrint)
    result shouldBe Left(APIGatewayAuthenticationError(UnexpectedDeliveryError))
  }

  it should "generate correct failure when Stannp has internal error" in {
    val result = StannpClient.send(httpClient(500, authorisationErrorResponse)).apply(pdfDocument, composedPrint)
    result shouldBe Left(APIGatewayInternalServerError(UnexpectedDeliveryError))
  }

  it should "generate correct failure when unspecified error received" in {
    val result = StannpClient.send(httpClient(404, authorisationErrorResponse)).apply(pdfDocument, composedPrint)
    result shouldBe Left(StannpConnectionError(UnexpectedDeliveryError))
  }
}
