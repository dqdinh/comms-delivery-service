package com.ovoenergy.delivery.service.print.stannp

import java.time.Clock

import com.ovoenergy.comms.model.{Print, PrintGatewayError, Stannp, UnexpectedDeliveryError}
import com.ovoenergy.delivery.config.StannpConfig
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.util.Retry
import okhttp3._
import cats.syntax.either._
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import io.circe.Decoder
import io.circe.generic.auto._
import io.circe.parser.parse

import scala.util.{Failure, Success, Try}

object StannpClient extends LoggingWithMDC {

  def send(httpClient: (Request) => Try[Response])(
      implicit stannpConfig: StannpConfig,
      clock: Clock): (PdfDocument, ComposedPrint) => Either[DeliveryError, GatewayComm] =
    (pdf: PdfDocument, event: ComposedPrint) => {

      val credentials = Credentials.basic(stannpConfig.apiKey, stannpConfig.password)

      val testDocument =
        if (event.metadata.canary || stannpConfig.test)
          "true"
        else
          "false"

      val request = new Request.Builder()
        .header("Authorization", credentials)
        .url(s"${stannpConfig.url}/api/v1/letters/post")
        .post(buildSendEmailForm(pdf, stannpConfig, testDocument))
        .build()

      val result = Retry.retry[DeliveryError, GatewayComm](Retry.constantDelay(stannpConfig.retry), _ => ()) { () =>
        httpClient(request) match {
          case Success(response) => handleStannpResponse(response, event)
          case Failure(e) => {
            logWarn(event, "Request to Stannp failed", e)
            Left(StannpConnectionError(UnexpectedDeliveryError))
          }
        }
      }

      result
        .leftMap(failed => failed.finalFailure)
        .map(succeeded => succeeded.result)
    }

  case class Data(id: String)
  case class SendPrintSuccessResponse(success: Boolean, data: Data)
  case class SendPrintFailureResponse(success: Boolean, error: String)

  private def handleStannpResponse(response: Response, event: ComposedPrint): Either[DeliveryError, GatewayComm] = {

    val responseBody = response.body().string()

    def handleFailedResponse(errorCode: Int) = errorCode match {
      case 401 => {
        logWarn(event, "Error sending print via Stannp API, authorization with Stannp API failed")
        Left(APIGatewayAuthenticationError(PrintGatewayError))
      }
      case 500 => {
        val error = parseResponse[SendPrintFailureResponse](responseBody)
          .map("- " + _.error)
          .getOrElse("Failed to parse error response from Stannp")
        logWarn(event, s"Error sending print via Stannp API, Stannp API internal error: ${response.code} - $error")
        Left(APIGatewayInternalServerError(PrintGatewayError))
      }
      case _ => {
        val error = parseResponse[SendPrintFailureResponse](responseBody)
          .map("- " + _.error)
          .getOrElse("Failed to parse error response from Stannp")
        logWarn(event, s"Error sending print via Stannp API, Stannp error code: $errorCode - $error")
        Left(StannpConnectionError(PrintGatewayError))
      }
    }

    response.code() match {
      case 200 => {
        val id = parseResponse[SendPrintSuccessResponse](responseBody).map(_.data.id).getOrElse("unknown id")
        Right(GatewayComm(Stannp, id, Print))
      }
      case errorCode => handleFailedResponse(errorCode)
    }
  }

  private def buildSendEmailForm(pdfDocument: PdfDocument, stannpConfig: StannpConfig, test: String)(
      implicit clock: Clock) = {
    new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart("test", test)
      .addFormDataPart("country", stannpConfig.country)
      .addFormDataPart("pdf", "pdf", RequestBody.create(MediaType.parse("application/pdf"), pdfDocument))
      .build()
  }

  private def parseResponse[T: Decoder](body: String): Either[Exception, T] = {
    parse(body).right.flatMap(_.as[T])
  }
}
