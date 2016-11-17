package com.ovoenergy.delivery.service.email.mailgun

import java.time.format.DateTimeFormatter
import java.time.{Clock, OffsetDateTime}
import java.util.UUID

import com.google.gson.{Gson, JsonParser}
import com.ovoenergy.comms.{ComposedEmail, Metadata}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import okhttp3.{Credentials, FormBody, Request, Response}

import scala.util.{Failure, Success, Try}

case class MailgunClientConfiguration(domain: String, apiKey: String)

case class CustomData(timestampIso8601: String, customerId: String, transactionId: String, friendlyDescription: String, canary: Boolean)

class MailgunClient(configuration: MailgunClientConfiguration, httpClient: (Request) => Try[Response], uuidGenerator: () => UUID)(implicit val clock: Clock) extends LoggingWithMDC {

  val loggerName = "MailgunClient"

  val credentials = Credentials.basic("api", configuration.apiKey)
  val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  def sendEmail(composedEmail: ComposedEmail): Either[EmailDeliveryError, EmailProgressed] = {

    val request = new Request.Builder()
      .header("Authorization", credentials)
      .url(s"https://api.mailgun.net/v3/${configuration.domain}/messages")
      .post(buildSendEmailForm(composedEmail))
      .build()

    httpClient(request) match {
      case Success(response) => mapResponseToEither(response, composedEmail)
      case Failure(ex) =>
        logError(composedEmail.metadata.transactionId, "Error sending email via Mailgun API", ex)
        Left(Exception)
    }
  }

  private def mapResponseToEither(response: Response, composedEmail: ComposedEmail) = {
    class Contains(r: Range) { def unapply(i: Int): Boolean = r contains i }
    val Success = new Contains(200 to 299)
    val InternalServerError = new Contains(500 to 599)

    response.code match {
      case Success() =>
        Right(EmailProgressed(buildMetadata(composedEmail), Queued, extractMessageIdFromResponse(response)))
      case InternalServerError() =>
        logError(composedEmail.metadata.transactionId, s"Error sending email via Mailgun API, Mailgun API internal error: ${response.code} - ${extractMessageFromResponse(response)}")
        Left(APIGatewayInternalServerError)
      case 401 =>
        logError(composedEmail.metadata.transactionId, "Error sending email via Mailgun API, authorization with Mailgun API failed")
        Left(APIGatewayAuthenticationError)
      case 400 =>
        logError(composedEmail.metadata.transactionId, s"Error sending email via Mailgun API, Bad request: '${extractMessageFromResponse(response)}'")
        Left(APIGatewayBadRequest)
      case _ =>
        logError(composedEmail.metadata.transactionId, s"Error sending email via Mailgun API, response code: ${response.code} - ${extractMessageFromResponse(response)}")
        Left(APIGatewayUnmappedError)
    }
  }

  private def buildSendEmailForm(composedEmail: ComposedEmail) = {
    val form = new FormBody.Builder()
      .add("from", composedEmail.sender)
      .add("to", composedEmail.recipient)
      .add("subject", composedEmail.subject)
      .add("html", composedEmail.htmlBody)
      .add("v:custom", buildCustomJson(composedEmail.metadata))

    if (composedEmail.textBody.isDefined) form.add("text", composedEmail.textBody.get).build()
    else form.build()
  }

  private def buildMetadata(composedEmail: ComposedEmail) = {
    composedEmail.metadata.copy(
      timestampIso8601 = OffsetDateTime.now(clock).format(dtf),
      kafkaMessageId = uuidGenerator(),
      source = "delivery-service",
      sourceMetadata = Some(composedEmail.metadata.copy(sourceMetadata = None))
    )
  }

  private def buildCustomJson(metadata: Metadata) = {
    new Gson().toJson(CustomData(
      timestampIso8601 = OffsetDateTime.now(clock).format(dtf),
      customerId = metadata.customerId,
      transactionId = metadata.transactionId,
      friendlyDescription = metadata.friendlyDescription,
      canary = metadata.canary))
  }

  private def extractMessageFromResponse(response: Response) = {
    if (response.message != null) {
      val json = new JsonParser().parse(response.message).getAsJsonObject
      if (json.has("message")) Some(json.get("message").getAsString)
      else None
    } else None
  }

  private def extractMessageIdFromResponse(response: Response) = {
    new JsonParser().parse(response.message).getAsJsonObject.get("id").getAsString
  }



}
