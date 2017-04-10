package com.ovoenergy.delivery.service.sms.twilio

import cats.syntax.either._
import com.ovoenergy.comms.model.Channel.SMS
import com.ovoenergy.comms.model.Gateway.Twilio
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.{Failed, RetryConfig, Succeeded}
import io.circe.generic.auto._
import io.circe.generic.extras.semiauto.deriveEnumerationEncoder
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import okhttp3.{Credentials, FormBody, Request, Response}

import scala.util.{Failure, Success, Try}

object TwilioClient extends LoggingWithMDC {
  case class Config(accountSid: String,
                    authToken: String,
                    serviceSid: String,
                    url: String,
                    retryConfig: Retry.RetryConfig,
                    httpClient: (Request) => Try[Response])

  case class ErrorResponse(detail: Option[String])

  case class SuccessfulResponse(sid: String)

  def send(config: Config): ComposedSMS => Either[DeliveryError, GatewayComm] = { event =>
    val request = {
      val credentials = Credentials.basic(config.accountSid, config.authToken)

      val requestBody = new FormBody.Builder()
        .add("Body", event.textBody)
        .add("To", event.recipient)
        .add("MessagingServiceSid", config.serviceSid)
        .build()

      new Request.Builder()
        .header("Authorization", credentials)
        .url(s"${config.url}/2010-04-01/Accounts/${config.accountSid}/Messages.json")
        .post(requestBody)
        .build()
    }

    val result = Retry.retry[DeliveryError, GatewayComm](config = config.retryConfig, onFailure = _ => ()) { () =>
      config.httpClient(request) match {
        case Success(response) => {
          extractResponse(response, event.metadata.traceToken)
        }
        case Failure(err) => {
          logWarn(event.metadata.traceToken, "Error sending SMS via twilio API", err)
          Left(ExceptionOccurred(ErrorCode.EmailGatewayError))
        }
      }
    }

    result
      .leftMap(_.finalFailure)
      .map(_.result)
  }

  private def extractResponse(response: Response, traceToken: String): Either[DeliveryError, GatewayComm] = {

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
          .leftMap(_ => ExceptionOccurred(ErrorCode.SMSGatewayError)) // Log exception
          .map { res =>
            logInfo(traceToken, s"SMS issued")
            GatewayComm(Twilio, res.sid, SMS)
          }
      }
      case InternalServerError() => {
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        logError(traceToken,
                 s"Error sending email via Twilio API, Twilio API internal error: ${response.code} $message")
        Left(APIGatewayInternalServerError(ErrorCode.SMSGatewayError))
      }
      case 401 =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        logError(traceToken, s"Error sending SMS via Twilio API, authorization with Twilio failed: $message")
        Left(APIGatewayAuthenticationError(ErrorCode.SMSGatewayError))
      case 400 =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        logError(traceToken, s"Error sending SMS via Twilio API, Bad request $message")
        Left(APIGatewayBadRequest(ErrorCode.SMSGatewayError))
      case _ =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        logError(traceToken, s"Error sending SMS via Twilio API, response code: ${response.code} $message")
        Left(APIGatewayUnspecifiedError(ErrorCode.SMSGatewayError))
    }
  }
}
