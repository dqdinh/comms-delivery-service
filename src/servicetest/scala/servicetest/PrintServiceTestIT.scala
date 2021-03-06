package servicetest

import java.io.File

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client, AmazonS3ClientBuilder, S3ClientOptions}
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers.withThrowawayConsumerFor
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.typesafe.config.ConfigFactory
import org.mockserver.client.server.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.time.{Seconds, Span}
import servicetest.aws.S3Client

import scala.language.reflectiveCalls
//Implicits
import scala.concurrent.duration._
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._

class PrintServiceTestIT
    extends DockerIntegrationTest
    with FlatSpecLike
    with S3Client
    with MockServer
    with Matchers
    with Arbitraries
    with ArbGenerator
    with BeforeAndAfterAll {

  implicit val pConfig: PatienceConfig = PatienceConfig(Span(60, Seconds))
  implicit val conf                    = ConfigFactory.load("servicetest.conf")

  val topics           = Kafka.aiven
  val bucketName = "ovo-comms-test"
  val url = s"s3-${conf.getString("aws.region")}.amazonaws.com"
  val testFile = "delivery-service/test.pdf"

  //https://ovo-comms-test.s3-eu-west-1.amazonaws.com/delivery-service/test.pdf
  behavior of "Print Delivery"

  it should "create failed event when authentication fails with Stannp" in {
    val composedPrintEvent = arbitraryComposedPrintEvent
    create401StannpResponse()
    uploadTestPdf(composedPrintEvent.pdfIdentifier)

    withThrowawayConsumerFor(Kafka.aiven.failed.v3) { consumer =>
      topics.composedPrint.v2.publishOnce(composedPrintEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.size shouldBe 1
      failedEvents.foreach(failed => {
        failed.reason shouldBe "Error authenticating with the Gateway"
        failed.errorCode shouldBe PrintGatewayError
      })
    }
  }

  it should "create a failed event when Stannp has internal issues" in {
    val composedPrintEvent = arbitraryComposedPrintEvent
    create500StannpResponse
    uploadTestPdf(composedPrintEvent.pdfIdentifier)

    withThrowawayConsumerFor(Kafka.aiven.failed.v3) { consumer =>
      topics.composedPrint.v2.publishOnce(composedPrintEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.size shouldBe 1
      failedEvents.foreach(failed => {
        failed.reason shouldBe "The Gateway had an error"
        failed.errorCode shouldBe PrintGatewayError
      })
    }
  }

  it should "create a IssuedForDelivery event when Stannp returns a 200 response (old bucket)" in {
    val composedPrintEvent = arbitraryComposedPrintEvent
    createOKStannpResponse()
    uploadTestPdf(composedPrintEvent.pdfIdentifier)

    withThrowawayConsumerFor(topics.issuedForDelivery.v3) { consumer =>
      topics.composedPrint.v2.publishOnce(composedPrintEvent, 10.seconds)
      val issuedForDeliveryEvents = consumer.pollFor(noOfEventsExpected = 1)
      issuedForDeliveryEvents.foreach(issuedForDelivery => {
        issuedForDelivery.gatewayMessageId shouldBe "1234"
        issuedForDelivery.gateway shouldBe Stannp
        issuedForDelivery.channel shouldBe Print
        issuedForDelivery.metadata.traceToken shouldBe composedPrintEvent.metadata.traceToken
        issuedForDelivery.internalMetadata.internalTraceToken shouldBe composedPrintEvent.internalMetadata.internalTraceToken
      })
    }
  }

  it should "create a failed event when cannot retrieve pdf from S3" in {
    val composedPrintEvent = arbitraryComposedPrintEvent.copy(pdfIdentifier = s"https://${bucketName}.$url/i-dont-exist.pdf")
    createOKStannpResponse()

    withThrowawayConsumerFor(Kafka.aiven.failed.v3) { consumer =>
      topics.composedPrint.v2.publishOnce(composedPrintEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1, pollTime = 180.seconds)
      failedEvents.size shouldBe 1
      failedEvents.foreach(failed => {
        failed.reason shouldBe s"Key i-dont-exist.pdf does not exist in bucket ${bucketName}."
        failed.errorCode shouldBe UnexpectedDeliveryError
      })
    }
  }

  def create401StannpResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/api/v1/letters/post")
      )
      .respond(
        response("""{"success":false,"error":"You do not have authorisation"}""")
          .withStatusCode(401)
      )
  }

  def create500StannpResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/api/v1/letters/post")
      )
      .respond(
        response("""{"success":false,"error":"Stannp internal error"}""")
          .withStatusCode(500)
      )
  }

  def createOKStannpResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/api/v1/letters/post")
      )
      .respond(
        response("""{"success":true,"data":{"id":"1234"}}""")
          .withStatusCode(200)
      )
  }

  def arbitraryComposedPrintEvent: ComposedPrintV2 =
    generate[ComposedPrintV2].copy(pdfIdentifier = s"https://${bucketName}.$url/${testFile}", expireAt = None)

  def uploadTestPdf(pdfIdentifier: String) = {
    val f = new File("test.pdf")
    s3Client.putObject(bucketName, testFile, f)
  }
}
