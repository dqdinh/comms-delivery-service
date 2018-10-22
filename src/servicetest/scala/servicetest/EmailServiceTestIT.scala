package servicetest

import java.io.File

import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.delivery.service.util.{ArbGenerator, LocalDynamoDb}
import com.typesafe.config.ConfigFactory
import org.mockserver.client.server.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}
import org.scalatest._
import servicetest.aws.S3Client

import scala.language.reflectiveCalls
//Implicits
import scala.concurrent.duration._
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._

class EmailServiceTestIT
    extends DockerIntegrationTest
    with FlatSpecLike
    with S3Client
    with MockServer
    with Matchers
    with GeneratorDrivenPropertyChecks
    with Arbitraries
    with ArbGenerator {

  implicit val pConfig: PatienceConfig = PatienceConfig(Span(60, Seconds))
  implicit val conf                    = ConfigFactory.load("servicetest.conf")

  val topics           = Kafka.aiven

  val bucketName = "ovo-comms-test"
  val testFile = "delivery-service/test.txt"
  val url = s"s3-${conf.getString("aws.region")}.amazonaws.com"

  override def beforeAll() = {
    super.beforeAll()
    uploadToNewBucket()
  }

  behavior of "Email Delivery"

  it should "create Failed event when authentication fails with Mailgun" in {
    create401MailgunResponse()

    withThrowawayConsumerFor(topics.failed.v3) { consumer =>
      val composedEmailEvent = arbitraryComposedEmailEvent
      topics.composedEmail.v4.publishOnce(composedEmailEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.size shouldBe 1
      failedEvents.foreach(failed => {
        failed.reason shouldBe "Error authenticating with the Gateway"
        failed.errorCode shouldBe EmailGatewayError
      })
    }
  }

  it should "create Failed event when get bad request from Mailgun" in {
    create400MailgunResponse()

    withThrowawayConsumerFor(topics.failed.v3) { consumer =>
      val composedEmailEvent = arbitraryComposedEmailEvent
      topics.composedEmail.v4.publishOnce(composedEmailEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.foreach(failed => {
        failed.reason shouldBe "The Gateway did not like our request"
        failed.errorCode shouldBe EmailGatewayError
      })
    }
  }

  it should "create IssuedForDelivery event when get OK from Mailgun" in {
    createOKMailgunResponse()
    withThrowawayConsumerFor(topics.issuedForDelivery.v3) { consumer =>
      val composedEmailEvent = arbitraryComposedEmailEvent

      topics.composedEmail.v4.publishOnce(composedEmailEvent, 10.seconds)
      val issuedForDeliveryEvents = consumer.pollFor(noOfEventsExpected = 1)
      issuedForDeliveryEvents.foreach(issuedForDelivery => {
        issuedForDelivery.gatewayMessageId shouldBe "ABCDEFGHIJKL1234"
        issuedForDelivery.gateway shouldBe Mailgun
        issuedForDelivery.channel shouldBe Email
        issuedForDelivery.metadata.traceToken shouldBe composedEmailEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedEmailEvent.internalMetadata.internalTraceToken
      })
    }
  }

  it should "raise failed event when a comm has already been delivered" in {
    createOKMailgunResponse()
    withThrowawayConsumerFor(topics.issuedForDelivery.v3, topics.failed.v3) {
      (issuedForDeliveryConsumer, failedConsumer) =>
        val composedEmailEvent = arbitraryComposedEmailEvent

        topics.composedEmail.v4.publishOnce(composedEmailEvent)
        topics.composedEmail.v4.publishOnce(composedEmailEvent)

        val issuedForDeliveryEvents = issuedForDeliveryConsumer.pollFor(noOfEventsExpected = 1)

        issuedForDeliveryEvents.foreach(issuedForDelivery => {
          issuedForDelivery.gatewayMessageId shouldBe "ABCDEFGHIJKL1234"
          issuedForDelivery.gateway shouldBe Mailgun
          issuedForDelivery.channel shouldBe Email
          issuedForDelivery.metadata.traceToken shouldBe composedEmailEvent.metadata.traceToken
          issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedEmailEvent.internalMetadata.internalTraceToken
        })

        val failedEvents = failedConsumer.pollFor(noOfEventsExpected = 1)

        failedEvents.foreach(failedEvent => {
          failedEvent.metadata.traceToken shouldBe composedEmailEvent.metadata.traceToken
          failedEvent.internalMetadata.internalTraceToken shouldBe composedEmailEvent.internalMetadata.internalTraceToken
        })
    }
  }

  it should "retry when Mailgun returns an error response" in {
    createFlakyMailgunResponse()

    withThrowawayConsumerFor(topics.issuedForDelivery.v3) { consumer =>
      val composedEmailEvent = arbitraryComposedEmailEvent

      topics.composedEmail.v4.publishOnce(composedEmailEvent, 10.seconds)
      val issuedForDeliveryEvents = consumer.pollFor(noOfEventsExpected = 1)

      issuedForDeliveryEvents.foreach(issuedForDelivery => {
        issuedForDelivery.gatewayMessageId shouldBe "ABCDEFGHIJKL1234"
        issuedForDelivery.gateway shouldBe Mailgun
        issuedForDelivery.channel shouldBe Email
        issuedForDelivery.metadata.traceToken shouldBe composedEmailEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedEmailEvent.internalMetadata.internalTraceToken
      })
    }
  }

  it should "Not do anything if dynamodb is unavailable" in {
    LocalDynamoDb.client().deleteTable("commRecord")
    withThrowawayConsumerFor(topics.issuedForDelivery.v3, topics.failed.v3) {
      (issuedForDeliveryConsumer, failedConsumer) =>
        val composedEmailEvent = arbitraryComposedEmailEvent

        topics.composedEmail.v4.publishOnce(composedEmailEvent)
        topics.composedEmail.v4.publishOnce(composedEmailEvent)
        issuedForDeliveryConsumer.checkNoMessages(10.seconds)
        failedConsumer.checkNoMessages(10.seconds)

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

  def arbitraryComposedEmailEvent: ComposedEmailV4 = {
    // Make sure the recipient email address is whitelisted
    val event = generate[ComposedEmailV4]
    event.copy(
      recipient = "foo@ovoenergy.com",
      expireAt = None,
      subject = s"https://${bucketName}.s3-eu-west-1.amazonaws.com/${testFile}",
      htmlBody = s"https://${bucketName}.s3-eu-west-1.amazonaws.com/${testFile}",
      textBody = Some(s"https://${bucketName}.s3-eu-west-1.amazonaws.com/${testFile}"),
      sender = s"https://${bucketName}.s3-eu-west-1.amazonaws.com/${testFile}",
    )
  }

  def uploadToNewBucket() = {
    val f = new File("test.txt")
    s3Client.putObject(bucketName, testFile, f)
  }
}
