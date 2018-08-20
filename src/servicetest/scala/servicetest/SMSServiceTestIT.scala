package servicetest

import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.templates.model.Brand
import com.ovoenergy.comms.templates.model.template.metadata.{TemplateId, TemplateSummary}
import com.ovoenergy.delivery.service.ConfigLoader
import com.ovoenergy.delivery.service.util.{ArbGenerator, LocalDynamoDb}
import com.typesafe.config.ConfigFactory
import org.mockserver.client.server.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalacheck.Arbitrary
import org.scalatest._
import org.scalatest.time.{Seconds, Span}

import scala.language.reflectiveCalls
import scala.io.Source
import scala.concurrent.duration._
import org.scalacheck.Arbitrary._
//implicits
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._

class SMSServiceTestIT
    extends DockerIntegrationTest
    with FlatSpecLike
    with Matchers
    with Arbitraries
    with ArbGenerator {

  implicit val conf    = ConfigFactory.load("servicetest.conf")
  implicit val pConfig = PatienceConfig(Span(60, Seconds))
  val mockServerClient = new MockServerClient("localhost", 1080)
  val twilioAccountSid = "test_account_SIIID"

  implicit val appConf = ConfigLoader.applicationConfig("servicetest.conf") match {
    case Left(e)    => log.error(s"Stopping application as config failed to load with error: $e"); sys.exit(1)
    case Right(res) => res
  }

  implicit val arbTemplateSummary = Arbitrary {
    for {
      tId   <- arbitrary[String]
      cName <- arbitrary[String]
      cType <- arbitrary[CommType]
      brand <- arbitrary[Brand]
      vers  <- arbitrary[String]
    } yield TemplateSummary(TemplateId(tId), cName, cType, brand, vers)
  }

  private def getFileString(file: String) = {
    Source
      .fromFile(file)
      .mkString
  }

  val validResponse           = getFileString("src/test/resources/SuccessfulTwilioResponse.json")
  val unauthenticatedResponse = getFileString("src/test/resources/FailedTwilioResponse.json")
  val badRequestResponse      = getFileString("src/test/resources/BadRequestTwilioResponse.json")

  behavior of "SMS Delivery"

  it should "create Failed event when authentication fails with Twilio" in {
    createTwilioResponse(401, unauthenticatedResponse)
    withThrowawayConsumerFor(Kafka.aiven.failed.v3) { consumer =>
      val composedSMSEvent = generate[ComposedSMSV4]
      val templateId       = TemplateId(composedSMSEvent.metadata.templateManifest.id)
      val templateSummary  = generate[TemplateSummary].copy(templateId = templateId)

      populateTemplateSummaryTable(templateSummary)

      Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent, 10.seconds)
      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.foreach { failed =>
        failed.errorCode shouldBe SMSGatewayError
        failed.reason shouldBe "Error authenticating with the Gateway"
      }
    }
  }

  it should "create Failed event when bad request from Twilio" in {
    createTwilioResponse(400, badRequestResponse)
    withThrowawayConsumerFor(Kafka.aiven.failed.v3) { consumer =>
      val composedSMSEvent = generate[ComposedSMSV4].copy(expireAt = None)
      val templateId       = TemplateId(composedSMSEvent.metadata.templateManifest.id)
      val templateSummary  = generate[TemplateSummary].copy(templateId = templateId)

      populateTemplateSummaryTable(templateSummary)

      Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent, 10.seconds)
      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.foreach { failed =>
        failed.errorCode shouldBe SMSGatewayError
        failed.reason shouldBe "The Gateway did not like our request"
      }
    }
  }

  it should "raise failed event when a comm has already been delivered" in {
    createTwilioResponse(200, validResponse)
    withThrowawayConsumerFor(Kafka.aiven.issuedForDelivery.v3, Kafka.aiven.failed.v3) {
      (issuedForDeliveryConsumer, failedConsumer) =>
        val composedSMSEvent = generate[ComposedSMSV4].copy(expireAt = None)
        val templateId       = TemplateId(composedSMSEvent.metadata.templateManifest.id)
        val templateSummary  = generate[TemplateSummary].copy(templateId = templateId)

        populateTemplateSummaryTable(templateSummary)

        Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent)
        Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent)

        val issuedForDeliveryEvents = issuedForDeliveryConsumer.pollFor(noOfEventsExpected = 1)

        issuedForDeliveryEvents.foreach(issuedForDelivery => {
          issuedForDelivery.gatewayMessageId shouldBe "1234567890"
          issuedForDelivery.gateway shouldBe Twilio
          issuedForDelivery.channel shouldBe SMS
          issuedForDelivery.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
          issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
        })

        val failedEvents = failedConsumer.pollFor(noOfEventsExpected = 1)

        failedEvents.foreach(failedEvent => {
          failedEvent.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
          failedEvent.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
        })
    }
  }

  it should "raise failed event when template summary is not found" in {
    createTwilioResponse(200, validResponse)
    withThrowawayConsumerFor(Kafka.aiven.issuedForDelivery.v3, Kafka.aiven.failed.v3) {
      (issuedForDeliveryConsumer, failedConsumer) =>
        val composedSMSEvent = generate[ComposedSMSV4].copy(expireAt = None)

        Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent)

        val failedEvents = failedConsumer.pollFor(noOfEventsExpected = 1)

        failedEvents.foreach(failedEvent => {
          failedEvent.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
          failedEvent.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
        })
    }
  }

  it should "create issued for delivery events when get OK from Twilio" in {
    createTwilioResponse(200, validResponse)

    withThrowawayConsumerFor(Kafka.aiven.issuedForDelivery.v3) { consumer =>
      val composedSMSEvent = generate[ComposedSMSV4].copy(expireAt = None)
      val templateId       = TemplateId(composedSMSEvent.metadata.templateManifest.id)
      val templateSummary  = generate[TemplateSummary].copy(templateId = templateId)

      populateTemplateSummaryTable(templateSummary)

      Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent, 10.seconds)
      val issuedForDeliveryEvents = consumer.pollFor(noOfEventsExpected = 1)

      issuedForDeliveryEvents.foreach(issuedForDelivery => {

        issuedForDelivery.gatewayMessageId shouldBe "1234567890"
        issuedForDelivery.gateway shouldBe Twilio
        issuedForDelivery.channel shouldBe SMS
        issuedForDelivery.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
      })
    }
  }

  it should "retry when Twilio returns an error response" in {
    createFlakyTwilioResponse()

    withThrowawayConsumerFor(Kafka.aiven.issuedForDelivery.v3) { consumer =>
      val composedSMSEvent = generate[ComposedSMSV4].copy(expireAt = None)
      val templateId       = TemplateId(composedSMSEvent.metadata.templateManifest.id)
      val templateSummary  = generate[TemplateSummary].copy(templateId = templateId)

      populateTemplateSummaryTable(templateSummary)

      Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent, 10.seconds)
      val issuedForDeliveryEvents = consumer.pollFor(noOfEventsExpected = 1)
      issuedForDeliveryEvents.foreach(issuedForDelivery => {

        issuedForDelivery.gatewayMessageId shouldBe "1234567890"
        issuedForDelivery.gateway shouldBe Twilio
        issuedForDelivery.channel shouldBe SMS
        issuedForDelivery.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
      })
    }
  }

  it should "Not do anything if dynamodb is unavailable" in {
    createTwilioResponse(200, validResponse)
    LocalDynamoDb.client().deleteTable("commRecord")
    withThrowawayConsumerFor(Kafka.aiven.issuedForDelivery.v3, Kafka.aiven.failed.v3) {
      (issuedForDeliveryConsumer, failedConsumer) =>
        val composedSMSEvent = generate[ComposedSMSV4].copy(expireAt = None)

        Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent)
        Kafka.aiven.composedSms.v4.publishOnce(composedSMSEvent)

        issuedForDeliveryConsumer.checkNoMessages(30.seconds)
        failedConsumer.checkNoMessages(30.seconds)
    }
  }

  def createTwilioResponse(statusCode: Int, responseStr: String) {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/2010-04-01/Accounts/$twilioAccountSid/Messages.json")
      )
      .respond(
        response(responseStr)
          .withStatusCode(statusCode)
      )
  }

  def createFlakyTwilioResponse() = {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/2010-04-01/Accounts/$twilioAccountSid/Messages.json"),
        Times.exactly(3)
      )
      .respond(
        response(badRequestResponse)
          .withStatusCode(400)
      )
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/2010-04-01/Accounts/$twilioAccountSid/Messages.json")
      )
      .respond(
        response(validResponse)
          .withStatusCode(200)
      )
  }

}
