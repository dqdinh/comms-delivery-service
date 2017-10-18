package com.ovoenergy.delivery.service.print.stannp

import java.time.Clock

import com.ovoenergy.comms.model.{Print, Stannp, UnexpectedDeliveryError}
import com.ovoenergy.delivery.config.StannpConfig
import com.ovoenergy.delivery.service.domain.{DeliveryError, GatewayComm, StannpConnectionError}
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.util.Retry
import okhttp3.{Credentials, FormBody, Request, Response}
import cats.syntax.either._
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient.parseResponse
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import io.circe.Decoder
import io.circe.parser.parse

import scala.util.{Failure, Success, Try}

object StannpClient extends LoggingWithMDC {

  def send(httpClient: (Request) => Try[Response])(implicit stannpConfig: StannpConfig, clock: Clock):
    (PdfDocument, ComposedPrint) => Either[DeliveryError, GatewayComm] =

    (pdf: PdfDocument, event: ComposedPrint) => {

      val credentials = Credentials.basic("api", stannpConfig.apiKey)

      val request = new Request.Builder()
        .header("Authorization", credentials)
        .url(stannpConfig.url)
        .post(buildSendEmailForm(pdf, stannpConfig))
        .build()

      val result = Retry.retry[DeliveryError, GatewayComm](Retry.constantDelay(stannpConfig.retry), _ => ()) {
        () =>
          httpClient(request) match {
            case Success(response) => handleStannpResponse(response, event)
            case Failure(e) => Left(StannpConnectionError(UnexpectedDeliveryError))
          }
      }

      result
        .leftMap(failed => failed.finalFailure)
        .map(succeeded => succeeded.result)
    }

  case class Data(id: String)
  case class SendPrintSuccessResponse(success: Boolean, data: Data)
  case class SendPrintFailureResponse(success: Boolean, error: String)

  private def handleStannpResponse(response: Response, event: ComposedPrint) = {

    val responseBody = response.body().string()
    val id = parseResponse[SendPrintSuccessResponse](responseBody).map(_.data.id).getOrElse("unknown id")

    response.code() match {
      case 200 => Right(GatewayComm(Stannp, id, Print))
      //    case 401 => ???
      //    case 404 => ???
      //    case 500 => ???
      case _ => Left(StannpConnectionError(UnexpectedDeliveryError))
    }
  }

  private def buildSendEmailForm(pdfDocument: PdfDocument, stannpConfig: StannpConfig)(implicit clock: Clock) = {
    new FormBody.Builder()
      .add("test", stannpConfig.test)
      .add("pdf", pdfDocument.toString)
      .add("country", stannpConfig.country)
      .build()
  }

  private def parseResponse[T: Decoder](body: String): Either[Exception, T] = {
    parse(body).right.flatMap(_.as[T])
  }
}
