package com.ovoenergy.delivery.service.email.mailgun

import java.lang.reflect.Type
import java.time.format.DateTimeFormatter
import java.time.{Clock, OffsetDateTime}

import cats.syntax.either._
import com.google.gson._
import com.ovoenergy.comms.model.EmailStatus.Queued
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.generic.extras.semiauto.deriveEnumerationEncoder
import io.circe.parser._
import io.circe.{Decoder, Encoder, Json}
import okhttp3.{Credentials, FormBody, Request, Response}

import scala.util.{Failure, Success, Try}

object MailgunClient extends LoggingWithMDC {

  case class Configuration(host: String, domain: String, apiKey: String, httpClient: (Request) => Try[Response])(implicit val clock: Clock)

  case class CustomFormData(createdAt: String, customerId: String, traceToken: String, canary: Boolean, commManifest: CommManifest)

  val loggerName = "MailgunClient"
  val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  implicit val encoder: Encoder[CommType] = deriveEnumerationEncoder[CommType]

  def apply(configuration: Configuration)(composedEmail: ComposedEmail): Either[EmailDeliveryError, EmailProgressed] = {
    case class SendEmailSuccessResponse(id: String, message: String)
    case class SendEmailFailureResponse(message: String)

    val traceToken = composedEmail.metadata.traceToken

    def mapResponseToEither(response: Response, composedEmail: ComposedEmail) = {
      class Contains(r: Range) {
        def unapply(i: Int): Boolean = r contains i
      }
      val Success = new Contains(200 to 299)
      val InternalServerError = new Contains(500 to 599)

      val responseBody = response.body().string()

      response.code match {
        case Success() =>
          val id = parseResponse[SendEmailSuccessResponse](responseBody).map(_.id).getOrElse("unknown id")
          logInfo(traceToken, s"Email issued to ${composedEmail.recipient}")
          Right(EmailProgressed(
            metadata = Metadata.fromSourceMetadata("delivery-service", composedEmail.metadata)
              .copy(createdAt = OffsetDateTime.now(configuration.clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
            status = Queued,
            gateway = "Mailgun",
            gatewayMessageId = Some(id)))
        case InternalServerError() =>
          val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
          logError(traceToken, s"Error sending email via Mailgun API, Mailgun API internal error: ${response.code} $message")
          Left(APIGatewayInternalServerError)
        case 401 =>
          logError(traceToken, "Error sending email via Mailgun API, authorization with Mailgun API failed")
          Left(APIGatewayAuthenticationError)
        case 400 =>
          val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
          logError(traceToken, s"Error sending email via Mailgun API, Bad request $message")
          Left(APIGatewayBadRequest)
        case _ =>
          val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
          logError(traceToken, s"Error sending email via Mailgun API, response code: ${response.code} $message")
          Left(APIGatewayUnspecifiedError)
      }
    }

    def buildSendEmailForm(composedEmail: ComposedEmail) = {
      val form = new FormBody.Builder()
        .add("from", composedEmail.sender)
        .add("to", composedEmail.recipient)
        .add("subject", composedEmail.subject)
        .add("html", composedEmail.htmlBody)
        .add("v:custom", buildCustomJson(composedEmail.metadata))

      composedEmail.textBody.fold(form.build())(textBody => form.add("text", textBody).build())
    }

    def buildCustomJson(metadata: Metadata): String = {
      CustomFormData(
        createdAt = OffsetDateTime.now(configuration.clock).format(dtf),
        customerId = metadata.customerId,
        traceToken = metadata.traceToken,
        canary = metadata.canary,
        commManifest = metadata.commManifest
      ).asJson.noSpaces.toString()
    }

    def parseResponse[T: Decoder](body: String): Either[Exception, T] = {
      parse(body) match {
        case Right(json) => json.as[T]
        case Left(ex) => Left(ex)
      }
    }

    val credentials = Credentials.basic("api", configuration.apiKey)
    val request = new Request.Builder()
      .header("Authorization", credentials)
      .url(s"${configuration.host}/v3/${configuration.domain}/messages")
      .post(buildSendEmailForm(composedEmail))
      .build()

    configuration.httpClient(request) match {
      case Success(response) => mapResponseToEither(response, composedEmail)
      case Failure(ex) =>
        logError(traceToken, "Error sending email via Mailgun API", ex)
        Left(ExceptionOccurred)
    }
  }
}
