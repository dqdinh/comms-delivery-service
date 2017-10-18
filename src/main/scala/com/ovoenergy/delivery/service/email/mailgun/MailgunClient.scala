package com.ovoenergy.delivery.service.email.mailgun

import java.time.format.DateTimeFormatter
import java.time.{Clock, OffsetDateTime}

import cats.syntax.either._
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.ComposedEmailV3
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
import okhttp3.{Credentials, FormBody, Request, Response}

import scala.util.{Failure, Success, Try}

object MailgunClient extends LoggingWithMDC {

  case class Configuration()(implicit val clock: Clock)

  case class CustomFormData(createdAt: String,
                            customerId: Option[String],
                            traceToken: String,
                            canary: Boolean,
                            commManifest: CommManifest,
                            internalTraceToken: String,
                            triggerSource: String)

  val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  implicit val encoder: Encoder[CommType] = deriveEnumerationEncoder[CommType]

  def sendEmail(httpClient: (Request) => Try[Response])(
      implicit mailgunConfig: MailgunAppConfig,
      clock: Clock): (ComposedEmailV3) => Either[DeliveryError, GatewayComm] = {
    val retryConfig =
      RetryConfig(mailgunConfig.retry.attempts, Retry.Backoff.constantDelay(mailgunConfig.retry.interval))

    (composedEmail: ComposedEmailV3) =>
      {

        val credentials = Credentials.basic("api", mailgunConfig.apiKey)
        val request = new Request.Builder()
          .header("Authorization", credentials)
          .url(s"${mailgunConfig.host}/v3/${mailgunConfig.domain}/messages")
          .post(buildSendEmailForm(composedEmail))
          .build()

        val result =
          Retry.retry[DeliveryError, GatewayComm](config = retryConfig, onFailure = _ => ()) { () =>
            httpClient(request) match {
              case Success(response) => mapResponseToEither(response, composedEmail)
              case Failure(ex) =>
                logError(composedEmail, "Error sending email via Mailgun API", ex)
                Left(ExceptionOccurred(EmailGatewayError))
            }
          }
        result
          .leftMap(failed => failed.finalFailure)
          .map(succeeded => succeeded.result)
      }
  }

  private def buildSendEmailForm(composedEmail: ComposedEmailV3)(implicit clock: Clock) = {
    val form = new FormBody.Builder()
      .add("from", composedEmail.sender)
      .add("to", composedEmail.recipient)
      .add("subject", composedEmail.subject)
      .add("html", composedEmail.htmlBody)
      .add("v:custom", buildCustomJson(composedEmail))

    composedEmail.textBody.fold(form.build())(textBody => form.add("text", textBody).build())
  }

  private def buildCustomJson(composedEmail: ComposedEmailV3)(implicit clock: Clock): String = {
    val customerId = composedEmail.metadata.deliverTo match {
      case Customer(cId) => Some(cId)
      case _             => None
    }

    CustomFormData(
      createdAt = OffsetDateTime.now(clock).format(dtf),
      customerId = customerId,
      traceToken = composedEmail.metadata.traceToken,
      canary = composedEmail.metadata.canary,
      commManifest = composedEmail.metadata.commManifest,
      internalTraceToken = composedEmail.internalMetadata.internalTraceToken,
      triggerSource = composedEmail.metadata.triggerSource
    ).asJson.noSpaces
  }

  private def mapResponseToEither(response: Response, composedEmail: ComposedEmailV3)(
      implicit clock: Clock): Either[DeliveryError, GatewayComm] = {
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
        logInfo(composedEmail, s"Email issued via Mailgun")
        Right(
          GatewayComm(
            gateway = Mailgun,
            id = id,
            channel = Email
          )
        )
      case InternalServerError() =>
        val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
        logWarn(composedEmail,
                s"Error sending email via Mailgun API, Mailgun API internal error: ${response.code} $message")
        Left(APIGatewayInternalServerError(EmailGatewayError))
      case 401 =>
        logWarn(composedEmail, "Error sending email via Mailgun API, authorization with Mailgun API failed")
        Left(APIGatewayAuthenticationError(EmailGatewayError))
      case 400 =>
        val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
        logWarn(composedEmail, s"Error sending email via Mailgun API, Bad request $message")
        Left(APIGatewayBadRequest(EmailGatewayError))
      case _ =>
        val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
        logWarn(composedEmail, s"Error sending email via Mailgun API, response code: ${response.code} $message")
        Left(APIGatewayUnspecifiedError(EmailGatewayError))
    }
  }

  private def parseResponse[T: Decoder](body: String): Either[Exception, T] = {
    parse(body).right.flatMap(_.as[T])
  }

}
