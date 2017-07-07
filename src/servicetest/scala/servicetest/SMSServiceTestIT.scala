package com.ovoenergy.delivery.service.service

import cakesolutions.kafka.KafkaProducer
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.delivery.service.service.helpers.KafkaTesting
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.apache.kafka.clients.producer.ProducerRecord
import org.mockserver.client.server.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}
import servicetest.DockerIntegrationTest

import scala.io.Source

//implicits
import org.scalacheck.Shapeless._

class SMSServiceTestIT
    extends DockerIntegrationTest
    with WordSpecLike
    with Matchers
    with GeneratorDrivenPropertyChecks
    with KafkaTesting
    with ArbGenerator {

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

  "SMS Delivery with legacy kafka" should {
    executeTestsWithKafkaInstance(legacyComposedSMSProducer)
  }

  "SMS Delivery with aiven kafka" should {
    executeTestsWithKafkaInstance(aivenComposedSMSProducer)
  }

  def executeTestsWithKafkaInstance(composedProducer: => KafkaProducer[String, ComposedSMSV2]) {
    "create Failed event when authentication fails with Twilio" in {
      createTwilioResponse(401, unauthenticatedResponse)
      val composedSMSEvent = generate[ComposedSMSV2]
      val future =
        composedProducer.send(new ProducerRecord[String, ComposedSMSV2](composedSMSTopic, composedSMSEvent))
      whenReady(future) { _ =>
        val failedEvents =
          pollForEvents(noOfEventsExpected = 1, consumer = aivenCommFailedConsumer, topic = failedTopic)
        val failed = failedEvents.head
        failed.errorCode shouldBe SMSGatewayError
        failed.reason shouldBe "Error authenticating with the Gateway"
      }
    }

    "create Failed event when bad request from Twilio" in {
      createTwilioResponse(400, badRequestResponse)
      val composedSMSEvent = generate[ComposedSMSV2]
      val future =
        composedProducer.send(new ProducerRecord[String, ComposedSMSV2](composedSMSTopic, composedSMSEvent))
      whenReady(future) { _ =>
        val failedEvents =
          pollForEvents(noOfEventsExpected = 1, consumer = aivenCommFailedConsumer, topic = failedTopic)

        val failed = failedEvents.head
        failed.errorCode shouldBe SMSGatewayError
        failed.reason shouldBe "The Gateway did not like our request"
      }
    }

    "create issued for delivery events when get OK from Twilio" in {
      createTwilioResponse(200, validResponse)
      val composedSMSEvent = generate[ComposedSMSV2]

      val future =
        composedProducer.send(new ProducerRecord[String, ComposedSMSV2](composedSMSTopic, composedSMSEvent))

      whenReady(future) { _ =>
        val issuedForDeliveryEvents =
          pollForEvents[IssuedForDeliveryV2](noOfEventsExpected = 1,
                                             consumer = aivenIssuedForDeliveryConsumer,
                                             topic = issuedForDeliveryTopic)

        issuedForDeliveryEvents.foreach(issuedForDelivery => {

          issuedForDelivery.gatewayMessageId shouldBe "1234567890"
          issuedForDelivery.gateway shouldBe Twilio
          issuedForDelivery.channel shouldBe SMS
          issuedForDelivery.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
          issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
        })
      }
    }

    "retry when Twilio returns an error response" in {
      createFlakyTwilioResponse()

      val composedSMSEvent = generate[ComposedSMSV2]
      val future =
        composedProducer.send(new ProducerRecord[String, ComposedSMSV2](composedSMSTopic, composedSMSEvent))

      whenReady(future) { _ =>
        val issuedForDeliveryEvents =
          pollForEvents[IssuedForDeliveryV2](noOfEventsExpected = 1,
                                             consumer = aivenIssuedForDeliveryConsumer,
                                             topic = issuedForDeliveryTopic)

        issuedForDeliveryEvents.foreach(issuedForDelivery => {

          issuedForDelivery.gatewayMessageId shouldBe "1234567890"
          issuedForDelivery.gateway shouldBe Twilio
          issuedForDelivery.channel shouldBe SMS
          issuedForDelivery.metadata.traceToken shouldBe composedSMSEvent.metadata.traceToken
          issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedSMSEvent.internalMetadata.internalTraceToken
        })
      }
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
