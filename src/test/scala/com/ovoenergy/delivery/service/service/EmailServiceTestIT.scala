package com.ovoenergy.delivery.service.service

import com.ovoenergy.comms.model.EmailStatus.Queued
import com.ovoenergy.comms.model.ErrorCode.EmailGatewayError
import com.ovoenergy.comms.model.Gateway.Mailgun
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.service.helpers.KafkaTesting
import org.apache.kafka.clients.producer.ProducerRecord
import org.mockserver.client.server.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalacheck.Arbitrary
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{Failed => _, _}
import scala.collection.JavaConverters._

//Implicits
import io.circe.generic.auto._
import org.scalacheck.Shapeless._

class EmailServiceTestIT
    extends FlatSpec
    with Matchers
    with GeneratorDrivenPropertyChecks
    with ScalaFutures
    with KafkaTesting
    with BeforeAndAfterAll {

  object DockerComposeTag extends Tag("DockerComposeTag")

  implicit val config: PatienceConfig = PatienceConfig(Span(60, Seconds))

  val mockServerClient = new MockServerClient("localhost", 1080)

  override def beforeAll() = {
    createTopicsAndSubscribe()
  }

  behavior of "Email Delivery"

  it should "create Failed event when authentication fails with Mailgun" taggedAs DockerComposeTag in {
    create401MailgunResponse()

    val composedEmailEvent = arbitraryComposedEmailEvent
    val future =
      composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, composedEmailEvent))
    whenReady(future) { _ =>
      val failedEvents =
        pollForEvents[Failed](noOfEventsExpected = 1, consumer = commFailedConsumer, topic = failedTopic)
      failedEvents.size shouldBe 1
      failedEvents.foreach(failed => {
        failed.reason shouldBe "Error authenticating with the Gateway"
        failed.errorCode shouldBe EmailGatewayError
      })
    }
  }

  it should "create Failed event when get bad request from Mailgun" taggedAs DockerComposeTag in {
    create400MailgunResponse()

    val composedEmailEvent = arbitraryComposedEmailEvent
    val future =
      composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, composedEmailEvent))
    whenReady(future) { _ =>
      val failedEvents = commFailedConsumer.poll(30000).records(failedTopic).asScala.toList
      failedEvents.size shouldBe 1
      failedEvents.foreach(record => {
        val failed = record.value().getOrElse(fail("No record for ${record.key()}"))
        failed.reason shouldBe "The Gateway did not like our request"
        failed.errorCode shouldBe ErrorCode.EmailGatewayError
      })
    }
  }

  it should "create IssuedForDelivery event when get OK from Mailgun" taggedAs DockerComposeTag in {
    createOKMailgunResponse()

    val composedEmailEvent = arbitraryComposedEmailEvent
    val future =
      composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, composedEmailEvent))
    whenReady(future) { _ =>
      val issuedForDeliveryEvents = pollForEvents[IssuedForDelivery](noOfEventsExpected = 1,
                                                                     consumer = issuedForDeliveryConsumer,
                                                                     topic = issuedForDeliveryTopic)

      issuedForDeliveryEvents.foreach(issuedForDelivery => {
        issuedForDelivery.gatewayMessageId shouldBe "ABCDEFGHIJKL1234"
        issuedForDelivery.gateway shouldBe Mailgun
        issuedForDelivery.channel shouldBe Channel.Email
        issuedForDelivery.metadata.traceToken shouldBe composedEmailEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedEmailEvent.internalMetadata.internalTraceToken
      })
    }
  }

  it should "retry when Mailgun returns an error response" taggedAs DockerComposeTag in {
    createFlakyMailgunResponse()

    val composedEmailEvent = arbitraryComposedEmailEvent
    val future =
      composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, composedEmailEvent))
    whenReady(future) { _ =>
      val issuedForDeliveryEvents = pollForEvents[IssuedForDelivery](noOfEventsExpected = 1,
                                                                     consumer = issuedForDeliveryConsumer,
                                                                     topic = issuedForDeliveryTopic)

      issuedForDeliveryEvents.foreach(issuedForDelivery => {
        issuedForDelivery.gatewayMessageId shouldBe "ABCDEFGHIJKL1234"
        issuedForDelivery.gateway shouldBe Mailgun
        issuedForDelivery.channel shouldBe Channel.Email
        issuedForDelivery.metadata.traceToken shouldBe composedEmailEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedEmailEvent.internalMetadata.internalTraceToken
      })
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

  def arbitraryComposedEmailEvent: ComposedEmail =
    // Make sure the recipient email address is whitelisted
    Arbitrary.arbitrary[ComposedEmail].sample.get.copy(recipient = "foo@ovoenergy.com")
}
