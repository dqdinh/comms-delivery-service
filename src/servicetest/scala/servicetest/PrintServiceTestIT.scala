package servicetest

import java.io.{File}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.{AmazonS3ClientBuilder, S3ClientOptions}
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
import scala.language.reflectiveCalls
//Implicits
import scala.concurrent.duration._
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._

class PrintServiceTestIT
    extends DockerIntegrationTest
    with FlatSpecLike
    with Matchers
    with Arbitraries
    with ArbGenerator
    with BeforeAndAfterAll {

  implicit val pConfig: PatienceConfig = PatienceConfig(Span(60, Seconds))
  implicit val conf                    = ConfigFactory.load("servicetest.conf")

  val mockServerClient = new MockServerClient("localhost", 1080)
  val topics           = Kafka.aiven

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

  it should "create a IssuedForDelivery event when Stannp returns a 200 response" in {
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
    val composedPrintEvent = arbitraryComposedPrintEvent.copy(pdfIdentifier = "i-dont-exist.pdf")
    createOKStannpResponse()

    withThrowawayConsumerFor(Kafka.aiven.failed.v3) { consumer =>
      topics.composedPrint.v2.publishOnce(composedPrintEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.size shouldBe 1
      failedEvents.foreach(failed => {
        failed.reason shouldBe "Key i-dont-exist.pdf does not exist in bucket dev-ovo-comms-pdfs."
        failed.errorCode shouldBe TemplateDownloadFailed
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
    generate[ComposedPrintV2].copy(pdfIdentifier = "example.pdf", expireAt = None)

  lazy val s3Client = {
    val creds           = new AWSStaticCredentialsProvider(new BasicAWSCredentials("service-test", "dummy"))
    val ep              = new EndpointConfiguration("http://localhost:4569", conf.getString("aws.region"))
    val s3clientOptions = S3ClientOptions.builder().setPathStyleAccess(true).disableChunkedEncoding().build()

    AmazonS3ClientBuilder
      .standard()
      .withCredentials(creds)
      .withEndpointConfiguration(ep)
      .withPathStyleAccessEnabled(true)
      .withChunkedEncodingDisabled(true)
      .build()
  }

  def uploadTestPdf(pdfIdentifier: String) = {
    s3Client.createBucket("dev-ovo-comms-pdfs")
    val f = new File("result.pdf")
    s3Client.putObject("dev-ovo-comms-pdfs", pdfIdentifier, f)
  }
}
