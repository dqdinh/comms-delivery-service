package com.ovoenergy.delivery.service.email.mailgun

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.time._
import java.time.format.DateTimeFormatter
import java.util.UUID

import com.ovoenergy.comms.{ComposedEmail, Metadata}
import okhttp3.{Protocol, Request, Response}
import okio.Okio
import org.scalatest.{FlatSpec, Matchers}

import scala.util.Try

class MailgunClientSpec extends FlatSpec
  with Matchers {

  val dateTime = OffsetDateTime.now()
  implicit val clock = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))

  val mailgunDomain = "jifjfofjosdfjdoisj"
  val mailgunApiKey = "dfsfsfdsfdsfs"
  val config = MailgunClientConfiguration(mailgunDomain, mailgunApiKey)

  val gatewayId = "<20161117104927.21714.32140.310532EA@sandbox98d59d0a8d0a4af588f2bb683a4a57cc.mailgun.org>"
  val successResponse = "{\n  \"id\": \"" + gatewayId + "\",\n  \"message\": \"Queued. Thank you.\"\n}"

  val transactionId = "fpwfj2i0jr02jr2j0"
  val timestamp = "2019-01-01T12:34:44.222Z"
  val customerId = "GT-CUS-994332344"
  val friendlyDescription = "The customer did something cool and wants to know"
  val from = "hello@ovoenergy.com"
  val to = "me@ovoenergy.com"
  val subject = "Some email subject"
  val htmlBody = "<html><head></head><body>Some email content</body></html>"
  val textBody = "Some email content"

  val mailgunResponseId = "<20161117104927.21714.32140.310532EA@sandbox98d59d0a8d0a4af588f2bb683a4a57cc.mailgun.org>"
  val mailgunResponseMessage = "Queued. Thank you."

  val composedEmailMetadata = Metadata(
    timestampIso8601 = timestamp,
    kafkaMessageId = UUID.randomUUID(),
    customerId = customerId,
    transactionId = transactionId,
    friendlyDescription = friendlyDescription,
    source = "tests",
    sourceMetadata = None,
    canary = false)

  val kafkaId = UUID.randomUUID()

  val composedEmail = ComposedEmail(composedEmailMetadata, from, to, subject, htmlBody, Some(textBody))

  behavior of "The Mailgun Client"

  it should "Send correct request to Mailgun API when only HTML present" in {
    val composedEmailHtmlOnly = ComposedEmail(composedEmailMetadata, from, to, subject, htmlBody, None)
    val okResponse = (request: Request) => {
      request.header("Authorization") shouldBe "Basic YXBpOmRmc2ZzZmRzZmRzZnM="
      request.url.toString shouldBe s"https://api.mailgun.net/v3/$mailgunDomain/messages"

      val out = new ByteArrayOutputStream
      val buffer = Okio.buffer(Okio.sink(out))
      request.body().writeTo(buffer)
      buffer.flush()
      assertFormData(out, false)

      Try[Response] {
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(200).message(successResponse).build()
      }
    }
    new MailgunClient(config, okResponse, () => kafkaId).sendEmail(composedEmailHtmlOnly) match {
      case Right(emailProgressed) =>
        emailProgressed.gatewayId shouldBe gatewayId
        emailProgressed.status shouldBe Queued
        assertMetadata(emailProgressed.metadata)
      case Left(_) => fail()
    }
  }

  it should "Send correct request to Mailgun API when both text and HTML present" in {
    val composedEmailWithText = ComposedEmail(composedEmailMetadata, from, to, subject, htmlBody, Some(textBody))
    val okResponse = (request: Request) => {
      request.header("Authorization") shouldBe "Basic YXBpOmRmc2ZzZmRzZmRzZnM="
      request.url.toString shouldBe s"https://api.mailgun.net/v3/$mailgunDomain/messages"

      val out = new ByteArrayOutputStream
      val buffer = Okio.buffer(Okio.sink(out))
      request.body().writeTo(buffer)
      buffer.flush()
      assertFormData(out, true)

      Try[Response] {
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(200).message(successResponse).build()
      }
    }
    new MailgunClient(config, okResponse, () => kafkaId).sendEmail(composedEmailWithText)
  }

  it should "Generate correct failure when an exception is thrown in the http client" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        throw new IllegalStateException("I am blown up")
      }
    }
    new MailgunClient(config, badResponse, () => kafkaId).sendEmail(composedEmail) match {
      case Right(_) => fail()
      case Left(failed) => failed shouldBe Exception
    }
  }

  it should "Generate correct failure for a 'bad request' response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(400).message("""{"message": "Some error message"}""").build()
      }
    }
    new MailgunClient(config, badResponse, () => kafkaId).sendEmail(composedEmail) match {
      case Right(_) => fail()
      case Left(failed) => failed shouldBe APIGatewayBadRequest
    }
  }

  it should "Generate correct failure for a '5xx' responses from Mailgun API" in {
    for (responseCode <- 500 to 511) {
      val badResponse = (request: Request) => {
        Try[Response] {
          new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(responseCode).build()
        }
      }
      new MailgunClient(config, badResponse, () => kafkaId).sendEmail(composedEmail) match {
        case Right(_) => fail()
        case Left(failed) => failed shouldBe APIGatewayInternalServerError
      }
    }
  }

  it should "Generate correct failure for a 'authorization error' response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(401).build()
      }
    }
    new MailgunClient(config, badResponse, () => kafkaId).sendEmail(composedEmail) match {
      case Right(_) => fail()
      case Left(failed) => failed shouldBe APIGatewayAuthenticationError
    }
  }

  it should "Generate correct failure for any other response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(422).build()
      }
    }
    new MailgunClient(config, badResponse, () => kafkaId).sendEmail(composedEmail) match {
      case Right(_) => fail()
      case Left(failed) => failed shouldBe APIGatewayUnmappedError
    }
  }

  private def assertMetadata(metadata: Metadata): Unit = {
    metadata.customerId shouldBe customerId
    metadata.canary shouldBe false
    metadata.friendlyDescription shouldBe friendlyDescription
    metadata.source shouldBe "delivery-service"
    metadata.sourceMetadata.get shouldBe composedEmailMetadata
    metadata.transactionId shouldBe transactionId
    metadata.timestampIso8601 shouldBe dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
  }

  private def assertFormData(out: ByteArrayOutputStream, textIncluded: Boolean) = {
    val formData = out.toString("UTF-8").split("&").map(formEntry => URLDecoder.decode(formEntry, "UTF-8"))
    formData should contain(s"from=$from")
    formData should contain(s"to=$to")
    formData should contain(s"subject=$subject")
    formData should contain(s"html=$htmlBody")
    if (textIncluded) formData should contain(s"text=$textBody")
    formData should contain("v:custom={" +
      "\"timestampIso8601\":\"" + dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\"," +
      "\"customerId\":\"" + customerId + "\"," +
      "\"transactionId\":\"" + transactionId + "\"," +
      "\"friendlyDescription\":\"" + friendlyDescription + "\"," +
      "\"canary\":false}"
    )
  }

}
