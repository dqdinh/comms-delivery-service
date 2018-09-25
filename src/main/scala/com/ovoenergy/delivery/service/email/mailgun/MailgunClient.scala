package com.ovoenergy.delivery.service.email.mailgun

import java.time.Clock
import java.time.format.DateTimeFormatter

import cats.effect.Sync
import cats.implicits._
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.config.MailgunAppConfig
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import io.circe.generic.auto._
import io.circe.generic.extras.semiauto.deriveEnumerationEncoder
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import okhttp3._

import scala.util.{Failure, Success, Try}

object MailgunClient extends LoggingWithMDC {

  val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  implicit val encoder: Encoder[CommType] = deriveEnumerationEncoder[CommType]

  def sendEmail[F[_]](httpClient: Request => Try[Response])(implicit mailgunConfig: MailgunAppConfig,
                                                            F: Sync[F]): Content.Email => F[GatewayComm] = {
    val retryConfig =
      RetryConfig(mailgunConfig.retry.attempts, Retry.Backoff.constantDelay(mailgunConfig.retry.interval))
    content: Content.Email =>
      val credentials = Credentials.basic("api", mailgunConfig.apiKey)
      val request = new Request.Builder()
        .header("Authorization", credentials)
        .url(s"${mailgunConfig.host}/v3/${mailgunConfig.domain}/messages")
        .post(buildSendEmailForm(content))
        .build()

      val r: F[Either[Throwable, GatewayComm]] = F.delay {
        Retry
          .retry[DeliveryError, GatewayComm](config = retryConfig, onFailure = _ => ()) { () =>
            httpClient(request) match {
              case Success(response) => mapResponseToEither(response)
              case Failure(ex) =>
                Left(ExceptionOccurred(EmailGatewayError, s"Error sending email via Mailgun API: ${ex.getMessage}"))
            }
          }
          .leftMap(_.finalFailure)
          .map(_.result)
      }

      r.rethrow
  }

  private def buildSendEmailForm(content: Content.Email): RequestBody = {
    val form = new FormBody.Builder()
      .add("from", content.sender.value)
      .add("to", content.recipient.value)
      .add("subject", content.subject.value)
      .add("html", content.htmlBody.value)
      .add("v:custom", content.customFormData.asJson.noSpaces)

    content.textBody.fold(form.build())(textBody => form.add("text", textBody.value).build())
  }

  private def mapResponseToEither(response: Response): Either[DeliveryError, GatewayComm] = {
    case class SendEmailSuccessResponse(id: String, message: String)
    case class SendEmailFailureResponse(message: String)

    class Contains(r: Range) {
      def unapply(i: Int): Boolean = r contains i
    }
    val Success             = new Contains(200 to 299)
    val InternalServerError = new Contains(500 to 599)

    val responseBody = response.body().string()

    response.code match {
      case Success() =>
        val id = parseResponse[SendEmailSuccessResponse](responseBody).map(_.id).getOrElse("unknown id")
        Right(
          GatewayComm(
            gateway = Mailgun,
            id = id,
            channel = com.ovoenergy.comms.model.Email
          )
        )
      case InternalServerError() =>
        val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
        Left(
          APIGatewayInternalServerError(
            EmailGatewayError,
            s"Error sending email via Mailgun API, Mailgun API internal error: ${response.code} $message"))
      case 401 =>
        Left(
          APIGatewayAuthenticationError(EmailGatewayError,
                                        "Error sending email via Mailgun API, authorization with Mailgun API failed"))
      case 400 =>
        val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
        Left(APIGatewayBadRequest(EmailGatewayError, s"Error sending email via Mailgun API, Bad request $message"))
      case _ =>
        val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
        Left(
          APIGatewayUnspecifiedError(EmailGatewayError,
                                     s"Error sending email via Mailgun API, response code: ${response.code} $message"))
    }
  }

  private def parseResponse[T: Decoder](body: String): Either[Exception, T] = {
    parse(body).right.flatMap(_.as[T])
  }
}
