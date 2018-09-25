package com.ovoenergy.delivery.service.print.stannp

import cats.effect.Sync
import cats.implicits._
import com.ovoenergy.comms.model.{Print, PrintGatewayError, Stannp, UnexpectedDeliveryError}
import com.ovoenergy.delivery.config.StannpConfig
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.Retry
import okhttp3._
import cats.syntax.either._
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import io.circe.Decoder
import io.circe.generic.auto._
import io.circe.parser.parse

import scala.util.{Failure, Success, Try}

object StannpClient extends LoggingWithMDC {

  def send[F[_]](httpClient: (Request) => Try[Response])(
      implicit stannpConfig: StannpConfig,
      F: Sync[F]): (Content.Print, ComposedPrintV2) => F[GatewayComm] =
    (content: Content.Print, event: ComposedPrintV2) => {

      val credentials = Credentials.basic(stannpConfig.apiKey, stannpConfig.password)

      val testDocument =
        if (event.metadata.canary || stannpConfig.test)
          "true"
        else
          "false"

      val request = new Request.Builder()
        .header("Authorization", credentials)
        .url(s"${stannpConfig.url}/api/v1/letters/post")
        .post(buildSendPrintForm(content, stannpConfig, testDocument))
        .build()

      val r: F[Either[Throwable, GatewayComm]] = F.delay {
        val result = Retry.retry[DeliveryError, GatewayComm](Retry.constantDelay(stannpConfig.retry), _ => ()) { () =>
          httpClient(request) match {
            case Success(response) => handleStannpResponse(response)
            case Failure(e) => {
              Left(StannpConnectionError(UnexpectedDeliveryError, s"Request to Stannp failed: ${e.getMessage}"))
            }
          }
        }

        result
          .leftMap(failed => failed.finalFailure)
          .map(succeeded => succeeded.result)
      }
      r.rethrow
    }

  case class Data(id: String)
  case class SendPrintSuccessResponse(success: Boolean, data: Data)
  case class SendPrintFailureResponse(success: Boolean, error: String)

  private def handleStannpResponse(response: Response): Either[DeliveryError, GatewayComm] = {

    val responseBody = response.body().string()

    def handleFailedResponse(errorCode: Int) = errorCode match {
      case 401 => {
        Left(
          APIGatewayAuthenticationError(PrintGatewayError,
                                        "Error sending print via Stannp API, authorization with Stannp API failed"))
      }
      case 500 => {
        val error = parseResponse[SendPrintFailureResponse](responseBody)
          .map("- " + _.error)
          .getOrElse("Failed to parse error response from Stannp")
        Left(
          APIGatewayInternalServerError(
            PrintGatewayError,
            s"Error sending print via Stannp API, Stannp API internal error: ${response.code} - $error"))
      }
      case _ => {
        val error = parseResponse[SendPrintFailureResponse](responseBody)
          .map("- " + _.error)
          .getOrElse("Failed to parse error response from Stannp")
        Left(
          StannpConnectionError(PrintGatewayError,
                                s"Error sending print via Stannp API, Stannp error code: $errorCode - $error"))
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

  private def buildSendPrintForm(content: Content.Print, stannpConfig: StannpConfig, test: String) = {
    new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart("test", test)
      .addFormDataPart("country", stannpConfig.country)
      .addFormDataPart("pdf", "pdf", RequestBody.create(MediaType.parse("application/pdf"), content.value))
      .build()
  }

  private def parseResponse[T: Decoder](body: String): Either[Exception, T] = {
    parse(body).right.flatMap(_.as[T])
  }
}
