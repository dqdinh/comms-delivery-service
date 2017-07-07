package com.ovoenergy.delivery.service.service

import cakesolutions.kafka.KafkaProducer
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.delivery.service.service.helpers.KafkaTesting
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.apache.kafka.clients.producer.ProducerRecord
import org.mockserver.client.server.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}
import org.scalatest._
import servicetest.DockerIntegrationTest

import scala.collection.JavaConverters._

//Implicits
import org.scalacheck.Shapeless._

class EmailServiceTestIT
    extends DockerIntegrationTest
    with WordSpecLike
    with Matchers
    with GeneratorDrivenPropertyChecks
    with KafkaTesting
    with ArbGenerator {

  implicit val config: PatienceConfig = PatienceConfig(Span(60, Seconds))

  val mockServerClient = new MockServerClient("localhost", 1080)

  "Email Delivery with legacy kafka" should {
    executeTestsWithKafkaInstance(legacyComposedEmailProducer)
  }

  "Email Delivery with aiven kafka" should {
    executeTestsWithKafkaInstance(aivenComposedEmailProducer)
  }

  def executeTestsWithKafkaInstance(composedProducer: => KafkaProducer[String, ComposedEmailV2]) {
    "create Failed event when authentication fails with Mailgun" in {
      create401MailgunResponse()

      val composedEmailEvent = arbitraryComposedEmailEvent
      val future =
        composedProducer.send(
          new ProducerRecord[String, ComposedEmailV2](composedEmailTopic, "test", composedEmailEvent))

      whenReady(future) { _ =>
        val failedEvents =
          pollForEvents[FailedV2](noOfEventsExpected = 1, consumer = aivenCommFailedConsumer, topic = failedTopic)
        failedEvents.size shouldBe 1
        failedEvents.foreach(failed => {
          failed.reason shouldBe "Error authenticating with the Gateway"
          failed.errorCode shouldBe EmailGatewayError
        })
      }
    }

    "create Failed event when get bad request from Mailgun" in {
      create400MailgunResponse()

      val composedEmailEvent = arbitraryComposedEmailEvent
      val future =
        composedProducer.send(new ProducerRecord[String, ComposedEmailV2](composedEmailTopic, composedEmailEvent))
      whenReady(future) { _ =>
        val failedEvents = aivenCommFailedConsumer.poll(30000).records(failedTopic).asScala.toList
        failedEvents.size shouldBe 1
        failedEvents.foreach(record => {
          val failed = record.value().getOrElse(fail("No record for ${record.key()}"))
          failed.reason shouldBe "The Gateway did not like our request"
          failed.errorCode shouldBe EmailGatewayError
        })
      }
    }

    "create IssuedForDelivery event when get OK from Mailgun" in {
      createOKMailgunResponse()

      val composedEmailEvent = arbitraryComposedEmailEvent
      val future =
        composedProducer.send(new ProducerRecord[String, ComposedEmailV2](composedEmailTopic, composedEmailEvent))
      whenReady(future) { _ =>
        val issuedForDeliveryEvents = pollForEvents[IssuedForDeliveryV2](noOfEventsExpected = 1,
                                                                         consumer = aivenIssuedForDeliveryConsumer,
                                                                         topic = issuedForDeliveryTopic)

        issuedForDeliveryEvents.foreach(issuedForDelivery => {
          issuedForDelivery.gatewayMessageId shouldBe "ABCDEFGHIJKL1234"
          issuedForDelivery.gateway shouldBe Mailgun
          issuedForDelivery.channel shouldBe Email
          issuedForDelivery.metadata.traceToken shouldBe composedEmailEvent.metadata.traceToken
          issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedEmailEvent.internalMetadata.internalTraceToken
        })
      }
    }

    "retry when Mailgun returns an error response" in {
      createFlakyMailgunResponse()

      val composedEmailEvent = arbitraryComposedEmailEvent
      val future =
        composedProducer.send(new ProducerRecord[String, ComposedEmailV2](composedEmailTopic, composedEmailEvent))
      whenReady(future) { _ =>
        val issuedForDeliveryEvents = pollForEvents[IssuedForDeliveryV2](noOfEventsExpected = 1,
                                                                         consumer = aivenIssuedForDeliveryConsumer,
                                                                         topic = issuedForDeliveryTopic)

        issuedForDeliveryEvents.foreach(issuedForDelivery => {
          issuedForDelivery.gatewayMessageId shouldBe "ABCDEFGHIJKL1234"
          issuedForDelivery.gateway shouldBe Mailgun
          issuedForDelivery.channel shouldBe Email
          issuedForDelivery.metadata.traceToken shouldBe composedEmailEvent.metadata.traceToken
          issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedEmailEvent.internalMetadata.internalTraceToken
        })
      }
    }
  }

  def create401MailgunResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/v3/mailgun@email.com/messages")
      )
      .respond(
        response("")
          .withStatusCode(401)
      )
  }

  def create400MailgunResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/v3/mailgun@email.com/messages")
      )
      .respond(
        response("""{"message": "Some error message"}""")
          .withStatusCode(400)
      )
  }

  def createOKMailgunResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/v3/mailgun@email.com/messages")
      )
      .respond(
        response("""{"message": "Email queued", "id": "ABCDEFGHIJKL1234"}""")
          .withStatusCode(200)
      )
  }

  def createFlakyMailgunResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/v3/mailgun@email.com/messages"),
        Times.exactly(3)
      )
      .respond(
        response("""{"message": "uh oh"}""")
          .withStatusCode(500)
      )
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/v3/mailgun@email.com/messages")
      )
      .respond(
        response("""{"message": "Email queued", "id": "ABCDEFGHIJKL1234"}""")
          .withStatusCode(200)
      )
  }

  def arbitraryComposedEmailEvent: ComposedEmailV2 =
    // Make sure the recipient email address is whitelisted
    generate[ComposedEmailV2].copy(recipient = "foo@ovoenergy.com")
}
