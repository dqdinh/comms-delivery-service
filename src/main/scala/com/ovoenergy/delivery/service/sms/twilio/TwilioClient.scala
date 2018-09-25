package com.ovoenergy.delivery.service.sms.twilio

import cats.effect.Sync
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.model.Brand
import com.ovoenergy.comms.templates.model.Brand._
import com.ovoenergy.delivery.config.{TwilioAppConfig, TwilioServiceSids}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.{Loggable, LoggingWithMDC}
import com.ovoenergy.delivery.service.util.Retry
import io.circe.Decoder
import io.circe.generic.auto._
import io.circe.parser._
import okhttp3.{Credentials, FormBody, Request, Response}
import cats.implicits._
import com.ovoenergy.delivery.service.domain.Content.Recipient

import scala.util.{Failure, Success, Try}

object TwilioClient extends LoggingWithMDC {
  case class ErrorResponse(detail: Option[String])
  case class SuccessfulResponse(sid: String)
  case class TwilioData(content: Content.SMS, brand: Brand, recipient: Recipient) // TODO: Find better name?

  implicit val loggableTD: Loggable[TwilioData] = Loggable.instance[TwilioData] { td =>
    Map().empty
  } // TODO: Sort out logging ting

  def send[F[_]](httpClient: (Request) => Try[Response])(implicit config: TwilioAppConfig,
                                                         F: Sync[F]): TwilioData => F[GatewayComm] = { data =>
    val retryConfig = Retry.constantDelay(config.retry)
    val request = {
      val credentials = Credentials.basic(config.accountSid, config.authToken)

      val requestBody = new FormBody.Builder()
        .add("Body", data.content.textBody.value)
        .add("To", data.recipient.value)
        .add("MessagingServiceSid", serviceSid(config.serviceSids, data.brand))
        .build()

      new Request.Builder()
        .header("Authorization", credentials)
        .url(s"${config.apiUrl}/2010-04-01/Accounts/${config.accountSid}/Messages.json")
        .post(requestBody)
        .build()
    }

    F.delay {
      Retry
        .retry[DeliveryError, GatewayComm](config = retryConfig, onFailure = _ => ()) { () =>
          httpClient(request) match {
            case Success(response) =>
              extractResponse(response, data.content)
            case Failure(err) =>
              Left(ExceptionOccurred(SMSGatewayError, s"Error sending SMS via twilio API: ${err.getMessage}"))
          }
        }
        .leftMap(_.finalFailure: Throwable)
        .map(_.result)
    }.rethrow
  }

  private def extractResponse(response: Response, content: Content.SMS): Either[DeliveryError, GatewayComm] = {

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
          .leftMap(err => ExceptionOccurred(SMSGatewayError, s"Error sending SMS via twilio: ${err.getMessage}")) // Log exception
          .map { res =>
            GatewayComm(Twilio, res.sid, SMS)
          }
      }
      case InternalServerError() => {
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        Left(
          APIGatewayInternalServerError(
            SMSGatewayError,
            s"Error sending SMS via Twilio API, Twilio API internal error: ${response.code} $message"))
      }
      case 401 =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        Left(
          APIGatewayAuthenticationError(
            SMSGatewayError,
            s"Error sending SMS via Twilio API, authorization with Twilio failed: $message"))
      case 400 =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        Left(APIGatewayBadRequest(SMSGatewayError, s"Error sending SMS via Twilio API, Bad request $message"))
      case _ =>
        val message = parseResponse[ErrorResponse](responseBody).map(_.detail).getOrElse(unknownResponseMsg)
        Left(
          APIGatewayUnspecifiedError(SMSGatewayError,
                                     s"Error sending SMS via Twilio API, response code: ${response.code} $message"))
    }
  }

  private[twilio] def serviceSid(sids: TwilioServiceSids, brand: Brand) = brand match {
    case Ovo   => sids.ovo
    case Boost => sids.boost
    case Corgi => sids.corgi
    case Lumo  => sids.lumo
    case Vnet  => sids.vnet
  }
}
