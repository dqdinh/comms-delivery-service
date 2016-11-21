package com.ovoenergy.delivery.service.email.mailgun

import java.time.format.DateTimeFormatter
import java.time.{Clock, OffsetDateTime}
import java.util.UUID

import cats.syntax.either._
import com.google.gson.Gson
import com.ovoenergy.comms.EmailStatus.Queued
import com.ovoenergy.comms.{ComposedEmail, EmailProgressed, Metadata}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import io.circe.Decoder
import io.circe.generic.auto._
import io.circe.parser._
import okhttp3.{Credentials, FormBody, Request, Response}

import scala.util.{Failure, Success, Try}

object MailgunClient extends LoggingWithMDC {

  case class Configuration(domain: String, apiKey: String, httpClient: (Request) => Try[Response], uuidGenerator: () => UUID)(implicit val clock: Clock)

  case class CustomFormData(timestampIso8601: String, customerId: String, transactionId: String, canary: Boolean)

  val loggerName = "MailgunClient"
  val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  def apply(configuration: Configuration)(composedEmail: ComposedEmail): Either[EmailDeliveryError, EmailProgressed] = {
    case class SendEmailSuccessResponse(id: String, message: String)
    case class SendEmailFailureResponse(message: String)

    val transactionId = composedEmail.metadata.transactionId

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
          Right(EmailProgressed(metadata = buildMetadata(composedEmail), status = Queued, gateway = "Mailgun", gatewayMessageId = id))
        case InternalServerError() =>
          val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
          logError(transactionId, s"Error sending email via Mailgun API, Mailgun API internal error: ${response.code} $message")
          Left(APIGatewayInternalServerError)
        case 401 =>
          logError(transactionId, "Error sending email via Mailgun API, authorization with Mailgun API failed")
          Left(APIGatewayAuthenticationError)
        case 400 =>
          val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
          logError(transactionId, s"Error sending email via Mailgun API, Bad request $message")
          Left(APIGatewayBadRequest)
        case _ =>
          val message = parseResponse[SendEmailFailureResponse](responseBody).map("- " + _.message).getOrElse("")
          logError(transactionId, s"Error sending email via Mailgun API, response code: ${response.code} $message")
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

    def buildMetadata(composedEmail: ComposedEmail) = {
      composedEmail.metadata.copy(
        timestampIso8601 = OffsetDateTime.now(configuration.clock).format(dtf),
        kafkaMessageId = configuration.uuidGenerator(),
        source = "delivery-service",
        sourceMetadata = Some(composedEmail.metadata.copy(sourceMetadata = None))
      )
    }

    def buildCustomJson(metadata: Metadata) = {
      new Gson().toJson(CustomFormData(
        timestampIso8601 = OffsetDateTime.now(configuration.clock).format(dtf),
        customerId = metadata.customerId,
        transactionId = metadata.transactionId,
        canary = metadata.canary))
    }

    def parseResponse[T: Decoder](body: String): Either[Exception, T] = {
      parse(body) match {
        case Right(json) => json.as[T]
        case Left(ex) =>
          logError(transactionId, s"Error parsing Mailgun response: $body", ex)
          Left(ex)
      }
    }

    val credentials = Credentials.basic("api", configuration.apiKey)
    val request = new Request.Builder()
      .header("Authorization", credentials)
      .url(s"https://api.mailgun.net/v3/${configuration.domain}/messages")
      .post(buildSendEmailForm(composedEmail))
      .build()

    configuration.httpClient(request) match {
      case Success(response) => mapResponseToEither(response, composedEmail)
      case Failure(ex) =>
        logError(transactionId, "Error sending email via Mailgun API", ex)
        Left(ExceptionOccurred)
    }
  }
}
