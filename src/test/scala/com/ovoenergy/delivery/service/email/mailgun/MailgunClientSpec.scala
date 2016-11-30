package com.ovoenergy.delivery.service.email.mailgun

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.time._
import java.time.format.DateTimeFormatter
import java.util.UUID

import com.ovoenergy.comms.model.EmailStatus.Queued
import com.ovoenergy.comms.model.{ComposedEmail, Metadata}
import okhttp3._
import okio.Okio
import org.scalatest.{FlatSpec, Matchers}

import scala.util.Try

class MailgunClientSpec extends FlatSpec
  with Matchers {

  val dateTime = OffsetDateTime.now()
  implicit val clock = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))

  val mailgunDomain = "jifjfofjosdfjdoisj"
  val mailgunApiKey = "dfsfsfdsfdsfs"
  val mailgunHost = "https://api.mailgun.net"

  val gatewayId = "<20161117104927.21714.32140.310532EA@sandbox98d59d0a8d0a4af588f2bb683a4a57cc.mailgun.org>"
  val successResponse = "{\n  \"id\": \"" + gatewayId + "\",\n  \"message\": \"Queued. Thank you.\"\n}"

  val traceToken = "fpwfj2i0jr02jr2j0"
  val createdAt = "2019-01-01T12:34:44.222Z"
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
    createdAt = createdAt,
    eventId = UUID.randomUUID().toString,
    customerId = customerId,
    traceToken = traceToken,
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
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(200).body(ResponseBody.create(MediaType.parse("UTF-8"), successResponse)).build()
      }
    }

    val config = MailgunClient.Configuration(mailgunHost, mailgunDomain, mailgunApiKey, okResponse)
    MailgunClient(config)(composedEmailHtmlOnly) match {
      case Right(emailProgressed) =>
        emailProgressed.gatewayMessageId shouldBe gatewayId
        emailProgressed.gateway shouldBe "Mailgun"
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
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(200).body(ResponseBody.create(MediaType.parse("UTF-8"), successResponse)).build()
      }
    }
    val config = MailgunClient.Configuration(mailgunHost, mailgunDomain, mailgunApiKey, okResponse)
    MailgunClient(config)(composedEmailWithText)
  }

  it should "Generate correct failure when an exception is thrown in the http client" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        throw new IllegalStateException("I am blown up")
      }
    }
    val config = MailgunClient.Configuration(mailgunHost, mailgunDomain, mailgunApiKey, badResponse)
    MailgunClient(config)(composedEmail) match {
      case Right(_) => fail()
      case Left(failed) => failed shouldBe ExceptionOccurred
    }
  }

  it should "Generate correct failure for a 'bad request' response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(400).body(ResponseBody.create(MediaType.parse("UTF-8"), """{"message": "Some error message"}""")).build()
      }
    }
    val config = MailgunClient.Configuration(mailgunHost, mailgunDomain, mailgunApiKey, badResponse)
    MailgunClient(config)(composedEmail) match {
      case Right(_) => fail()
      case Left(failed) => failed shouldBe APIGatewayBadRequest
    }
  }

  it should "Generate correct failure for a '5xx' responses from Mailgun API" in {
    for (responseCode <- 500 to 511) {
      val badResponse = (request: Request) => {
        Try[Response] {
          new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(responseCode).body(ResponseBody.create(MediaType.parse("UTF-8"), "")).build()
        }
      }
      val config = MailgunClient.Configuration(mailgunHost, mailgunDomain, mailgunApiKey, badResponse)
      MailgunClient(config)(composedEmail) match {
        case Right(_) => fail()
        case Left(failed) => failed shouldBe APIGatewayInternalServerError
      }
    }
  }

  it should "Generate correct failure for a 'authorization error' response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(401).body(ResponseBody.create(MediaType.parse("UTF-8"), "")).build()
      }
    }
    val config = MailgunClient.Configuration(mailgunHost, mailgunDomain, mailgunApiKey, badResponse)
    MailgunClient(config)(composedEmail) match {
      case Right(_) => fail()
      case Left(failed) => failed shouldBe APIGatewayAuthenticationError
    }
  }

  it should "Generate correct failure for any other response from Mailgun API" in {
    val badResponse = (request: Request) => {
      Try[Response] {
        new Response.Builder().protocol(Protocol.HTTP_1_1).request(request).code(422).body(ResponseBody.create(MediaType.parse("UTF-8"), "")).build()
      }
    }
    val config = MailgunClient.Configuration(mailgunHost, mailgunDomain, mailgunApiKey, badResponse)
    MailgunClient(config)(composedEmail) match {
      case Right(_) => fail()
      case Left(failed) => failed shouldBe APIGatewayUnspecifiedError
    }
  }

  private def assertMetadata(metadata: Metadata): Unit = {
    metadata.customerId shouldBe customerId
    metadata.canary shouldBe false
    metadata.source shouldBe "delivery-service"
    metadata.sourceMetadata.get shouldBe composedEmailMetadata
    metadata.traceToken shouldBe traceToken
    metadata.createdAt shouldBe dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    metadata.friendlyDescription shouldBe friendlyDescription
  }

  private def assertFormData(out: ByteArrayOutputStream, textIncluded: Boolean) = {
    val formData = out.toString("UTF-8").split("&").map(formEntry => URLDecoder.decode(formEntry, "UTF-8"))
    formData should contain(s"from=$from")
    formData should contain(s"to=$to")
    formData should contain(s"subject=$subject")
    formData should contain(s"html=$htmlBody")
    if (textIncluded) formData should contain(s"text=$textBody")
    formData should contain("v:custom={" +
      "\"createdAt\":\"" + dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\"," +
      "\"customerId\":\"" + customerId + "\"," +
      "\"traceToken\":\"" + traceToken + "\"," +
      "\"canary\":false}"
    )
  }

}
