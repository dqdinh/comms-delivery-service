package com.ovoenergy.delivery.service.service

import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.delivery.service.service.helpers.KafkaTesting
import org.apache.kafka.clients.producer.ProducerRecord
import org.mockserver.client.server.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalacheck.Arbitrary
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}
import scala.io.Source

//implicits
import com.ovoenergy.comms.serialisation.Decoders._
import io.circe.generic.auto._
import org.scalacheck.Shapeless._

class SMSServiceTestIT
    extends FlatSpec
    with Matchers
    with GeneratorDrivenPropertyChecks
    with ScalaFutures
    with KafkaTesting
    with BeforeAndAfterAll {

  object DockerComposeTag extends Tag("DockerComposeTag")

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

  override def beforeAll() = {
    createTopicsAndSubscribe()
  }

  behavior of "SMS Delivery"

  it should "create Failed event when authentication fails with Twilio" taggedAs DockerComposeTag in {
    createTwilioResponse(401, unauthenticatedResponse)
    val composedSMSEvent = arbitraryComposedSMSEvent
    val future =
      composedSMSProducer.send(new ProducerRecord[String, ComposedSMSV2](composedSMSTopic, composedSMSEvent))
    whenReady(future) { _ =>
      val failedEvents = pollForEvents(noOfEventsExpected = 1, consumer = commFailedConsumer, topic = failedTopic)
      val failed       = failedEvents.head
      failed.errorCode shouldBe ErrorCode.SMSGatewayError
      failed.reason shouldBe "Error authenticating with the Gateway"
    }
  }

  it should "create Failed event when bad request from Twilio" taggedAs DockerComposeTag in {
    createTwilioResponse(400, badRequestResponse)
    val composedSMSEvent = arbitraryComposedSMSEvent
    val future =
      composedSMSProducer.send(new ProducerRecord[String, ComposedSMSV2](composedSMSTopic, composedSMSEvent))
    whenReady(future) { _ =>
      val failedEvents = pollForEvents(noOfEventsExpected = 1, consumer = commFailedConsumer, topic = failedTopic)

      val failed = failedEvents.head
      failed.errorCode shouldBe ErrorCode.SMSGatewayError
      failed.reason shouldBe "The Gateway did not like our request"
    }
  }

  it should "create issued for delivery events when get OK from Twilio" taggedAs DockerComposeTag in {
    createTwilioResponse(200, validResponse)
    val composedSMSEvent = arbitraryComposedSMSEvent

    val future =
      composedSMSProducer.send(new ProducerRecord[String, ComposedSMSV2](composedSMSTopic, composedSMSEvent))

    whenReady(future) { _ =>
      val issuedForDeliveryEvents =
        pollForEvents[IssuedForDeliveryV2](noOfEventsExpected = 1,
                                           consumer = issuedForDeliveryConsumer,
                                           topic = issuedForDeliveryTopic)

      issuedForDeliveryEvents.foreach(issuedForDelivery => {

        issuedForDelivery.gatewayMessageId shouldBe "1234567890"
        issuedForDelivery.gateway shouldBe Twilio
        issuedForDelivery.channel shouldBe Channel.SMS
        issuedForDelivery.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
      })
    }
  }

  it should "retry when Twilio returns an error response" taggedAs DockerComposeTag in {
    createFlakyTwilioResponse()

    val composedSMSEvent = arbitraryComposedSMSEvent
    val future =
      composedSMSProducer.send(new ProducerRecord[String, ComposedSMSV2](composedSMSTopic, composedSMSEvent))

    whenReady(future) { _ =>
      val issuedForDeliveryEvents =
        pollForEvents[IssuedForDeliveryV2](noOfEventsExpected = 1,
                                           consumer = issuedForDeliveryConsumer,
                                           topic = issuedForDeliveryTopic)

      issuedForDeliveryEvents.foreach(issuedForDelivery => {

        issuedForDelivery.gatewayMessageId shouldBe "1234567890"
        issuedForDelivery.gateway shouldBe Twilio
        issuedForDelivery.channel shouldBe Channel.SMS
        issuedForDelivery.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
      })
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

  def arbitraryComposedSMSEvent: ComposedSMSV2 =
    Arbitrary.arbitrary[ComposedSMSV2].sample.get

}
