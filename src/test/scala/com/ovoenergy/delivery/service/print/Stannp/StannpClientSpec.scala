package com.ovoenergy.delivery.service.print.Stannp

import java.io.ByteArrayOutputStream
import java.time.{Clock, OffsetDateTime, ZoneId}

import scala.concurrent.duration._
import akka.Done
import com.ovoenergy.comms.model.{Email, Mailgun, Print, Stannp}
import com.ovoenergy.comms.model.email.ComposedEmailV3
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.config.{ConstantDelayRetry, StannpConfig}
import com.ovoenergy.delivery.service.domain.{DeliveryError, GatewayComm}
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

class StannpClientSpec extends FlatSpec with Matchers with ArbGenerator with EitherValues{
  val dateTime = OffsetDateTime.now(ZoneId.of("UTC"))

  val url = "https://dash.stannp.com/api/v1/letters/post"
  val test = "true"
  val country = "GB"
  val apiKey = ""
  val retry = ConstantDelayRetry(refineV[Positive](1).right.get, 1.second)

  implicit val clock = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))
  implicit val stannpConfig = StannpConfig(url, apiKey, country, test, retry)

  val composedPrint = generate[ComposedPrint]
  val printSentRes  = generate[Done]
  val deliveryError = generate[DeliveryError]
  val pdfDocument   = generate[PdfDocument]

  val successResponse = ""

  it should "work" in {

    val assertions: Request => Unit = (request: Request) => {
      request.header("Authorization") shouldBe "Basic YXBpOg=="
      request.url.toString shouldBe url
    }

    val httpClient = (request: Request) => {
      val out    = new ByteArrayOutputStream
      val buffer = Okio.buffer(Okio.sink(out))
      request.body().writeTo(buffer)
      buffer.flush()
      assertions(request)

      Try[Response] {
        new Response.Builder()
          .protocol(Protocol.HTTP_1_1)
          .request(request)
          .code(200)
          .body(ResponseBody.create(MediaType.parse("UTF-8"), successResponse))
          .build()
      }
    }

    val result = StannpClient.send(httpClient).apply(pdfDocument, composedPrint)
    result shouldBe Right(GatewayComm(gateway = Stannp, id = "", channel = Print))
  }
}
