package servicetest

import java.io.{BufferedReader, File, FileReader}
import java.nio.file.{Files, Paths}

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client, AmazonS3ClientBuilder, S3ClientOptions}
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers.withThrowawayConsumerFor
import com.ovoenergy.delivery.config
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.typesafe.config.ConfigFactory
import org.mockserver.client.server.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.reflectiveCalls
//Implicits
import com.ovoenergy.comms.serialisation.Codecs._
import org.scalacheck.Shapeless._
import scala.concurrent.duration._
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers._

class PrintServiceTestIT
    extends DockerIntegrationTest
    with FlatSpecLike
    with Matchers
    with GeneratorDrivenPropertyChecks
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

    withThrowawayConsumerFor(Kafka.aiven.failed.v2) { consumer =>
      topics.composedPrint.v1.publishOnce(composedPrintEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.size shouldBe 1
      failedEvents.foreach(failed => {
        failed.reason shouldBe "Error authenticating with the Gateway"
        failed.errorCode shouldBe UnexpectedDeliveryError
      })
    }
  }

  it should "create a failed event when Stannp has internal issues" in {
    val composedPrintEvent = arbitraryComposedPrintEvent
    create500StannpResponse
    uploadTestPdf(composedPrintEvent.pdfIdentifier)

    withThrowawayConsumerFor(Kafka.aiven.failed.v2) { consumer =>
      topics.composedPrint.v1.publishOnce(composedPrintEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.size shouldBe 1
      failedEvents.foreach(failed => {
        failed.reason shouldBe "The Gateway had an error"
        failed.errorCode shouldBe UnexpectedDeliveryError
      })
    }
  }

  it should "create a IssuedForDelivery event when Stannp returns a 200 response" in {
    val composedPrintEvent = arbitraryComposedPrintEvent
    createOKStannpResponse()
    uploadTestPdf(composedPrintEvent.pdfIdentifier)

    withThrowawayConsumerFor(topics.issuedForDelivery.v2) { consumer =>
      topics.composedPrint.v1.publishOnce(composedPrintEvent, 10.seconds)
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

    withThrowawayConsumerFor(Kafka.aiven.failed.v2) { consumer =>
      topics.composedPrint.v1.publishOnce(composedPrintEvent, 10.seconds)

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

  def arbitraryComposedPrintEvent: ComposedPrint =
    generate[ComposedPrint].copy(pdfIdentifier = "example.pdf")

  lazy val clientMk2 = {
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

  private def uploadTemplateToS3(): Unit = {
    // disable chunked encoding to work around https://github.com/jubos/fake-s3/issues/164

    clientMk2.createBucket("ovo-comms-templates")
    clientMk2.createBucket("dev-ovo-comms-pdfs")

    // template
    clientMk2.putObject("ovo-comms-templates",
                        "service/composer-service-test/0.1/email/subject.txt",
                        "SUBJECT {{profile.firstName}}")
    clientMk2.putObject("ovo-comms-templates",
                        "service/composer-service-test/0.1/email/body.html",
                        "{{> header}} HTML BODY {{amount}}")
    clientMk2.putObject("ovo-comms-templates",
                        "service/composer-service-test/0.1/email/body.txt",
                        "{{> header}} TEXT BODY {{amount}}")
    clientMk2.putObject("ovo-comms-templates",
                        "service/composer-service-test/0.1/sms/body.txt",
                        "{{> header}} SMS BODY {{amount}}")
    clientMk2.putObject("ovo-comms-templates",
                        "service/composer-service-test/0.1/print/body.html",
                        "Hello {{profile.firstName}}")

    // fragments
    clientMk2.putObject("ovo-comms-templates", "service/fragments/email/html/header.html", "HTML HEADER")
    clientMk2.putObject("ovo-comms-templates", "service/fragments/email/txt/header.txt", "TEXT HEADER")
    clientMk2.putObject("ovo-comms-templates", "service/fragments/sms/txt/header.txt", "SMS HEADER")
  }

  def uploadTestPdf(pdfIdentifier: String) = {
    clientMk2.createBucket("dev-ovo-comms-pdfs")
    println(s"Pdf ID: ${pdfIdentifier}")
    val f = new File("result.pdf")
    clientMk2.putObject("dev-ovo-comms-pdfs", pdfIdentifier, f)
  }
}
