package servicetest

import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.delivery.service.util.{ArbGenerator, LocalDynamoDb}
import com.typesafe.config.ConfigFactory
import org.mockserver.client.server.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.reflectiveCalls
import scala.io.Source
import scala.concurrent.duration._

//implicits
import com.ovoenergy.comms.serialisation.Codecs._
import org.scalacheck.Shapeless._
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._

class SMSServiceTestIT
    extends DockerIntegrationTest
    with FlatSpecLike
    with Matchers
    with GeneratorDrivenPropertyChecks
    with ArbGenerator {

  implicit val conf    = ConfigFactory.load("servicetest.conf")
  implicit val pConfig = PatienceConfig(Span(60, Seconds))
  val mockServerClient = new MockServerClient("localhost", 1080)
  val twilioAccountSid = "test_account_SIIID"

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
      val composedSMSEvent = generate[ComposedSMSV4]

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
        val composedSMSEvent = generate[ComposedSMSV4]

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

  it should "create issued for delivery events when get OK from Twilio" in {
    createTwilioResponse(200, validResponse)

    withThrowawayConsumerFor(Kafka.aiven.issuedForDelivery.v3) { consumer =>
      val composedSMSEvent = generate[ComposedSMSV4]

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
      val composedSMSEvent = generate[ComposedSMSV4]

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
        val composedSMSEvent = generate[ComposedSMSV4]

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
