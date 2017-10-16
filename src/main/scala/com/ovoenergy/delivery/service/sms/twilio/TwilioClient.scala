package com.ovoenergy.delivery.service.sms.twilio

import cats.syntax.either._
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms.{ComposedSMSV2, ComposedSMSV3}
import com.ovoenergy.delivery.config.TwilioAppConfig
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import io.circe.Decoder
import io.circe.generic.auto._
import io.circe.parser._
import okhttp3.{Credentials, FormBody, Request, Response}

import scala.util.{Failure, Success, Try}

object TwilioClient extends LoggingWithMDC {
  case class ErrorResponse(detail: Option[String])
  case class SuccessfulResponse(sid: String)

  def send(httpClient: (Request) => Try[Response])(
      implicit config: TwilioAppConfig): ComposedSMSV3 => Either[DeliveryError, GatewayComm] = { event =>
    val retryConfig = Retry.constantDelay(config.retry)
    val request = {
      val credentials = Credentials.basic(config.accountSid, config.authToken)

      val requestBody = new FormBody.Builder()
        .add("Body", event.textBody)
        .add("To", event.recipient)
        .add("MessagingServiceSid", config.serviceSid)
        .build()

      new Request.Builder()
        .header("Authorization", credentials)
        .url(s"${config.apiUrl}/2010-04-01/Accounts/${config.accountSid}/Messages.json")
        .post(requestBody)
        .build()
    }

    val result = Retry.retry[DeliveryError, GatewayComm](config = retryConfig, onFailure = _ => ()) { () =>
      httpClient(request) match {
        case Success(response) => {
          extractResponse(response, event)
        }
        case Failure(err) => {
          logWarn(event, "Error sending SMS via twilio API", err)
          Left(ExceptionOccurred(SMSGatewayError))
        }
      }
    }

    result
      .leftMap(_.finalFailure)
      .map(_.result)
  }

  private def extractResponse(response: Response, composedSMS: ComposedSMSV3): Either[DeliveryError, GatewayComm] = {

    val unknownResponseMsg = "Unknown response from twilio api"
    class Contains(r: Range) {
      def unapply(i: Int): Boolean = r contains i
    }
    val InternalServerError = new Contains(500 to 599)

    def parseResponse[T: Decoder](body: String): Either[Exception, T] = {
      parse(body).right.flatMap(_.as[T])
    }

    val responseBody = response.body().string()

    response.code() match {
      case success if response.isSuccessful => {
        parseResponse[SuccessfulResponse](responseBody)
          .leftMap(_ => ExceptionOccurred(SMSGatewayError)) // Log exception
          .map { res =>
            logInfo(composedSMS, s"SMS issued")
            GatewayComm(Twilio, res.sid, SMS)
          }
      }
      case InternalServerError() => {
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        logWarn(composedSMS, s"Error sending SMS via Twilio API, Twilio API internal error: ${response.code} $message")
        Left(APIGatewayInternalServerError(SMSGatewayError))
      }
      case 401 =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        logWarn(composedSMS, s"Error sending SMS via Twilio API, authorization with Twilio failed: $message")
        Left(APIGatewayAuthenticationError(SMSGatewayError))
      case 400 =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        logWarn(composedSMS, s"Error sending SMS via Twilio API, Bad request $message")
        Left(APIGatewayBadRequest(SMSGatewayError))
      case _ =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        logWarn(composedSMS, s"Error sending SMS via Twilio API, response code: ${response.code} $message")
        Left(APIGatewayUnspecifiedError(SMSGatewayError))
    }
  }
}
