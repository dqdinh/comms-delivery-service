package servicetest

import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.comms.testhelpers.KafkaTestHelpers.withThrowawayConsumerFor
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.typesafe.config.ConfigFactory
import org.mockserver.client.server.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.{FlatSpecLike, Matchers}
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

class PrintServiceTestIT extends DockerIntegrationTest
  with FlatSpecLike
  with Matchers
  with GeneratorDrivenPropertyChecks
  with ArbGenerator {

  implicit val pConfig: PatienceConfig = PatienceConfig(Span(60, Seconds))
  implicit val conf                    = ConfigFactory.load("servicetest.conf")

  val mockServerClient = new MockServerClient("localhost", 1080)
  val topics           = Kafka.aiven

  behavior of "Print Delivery"

  it should "create failed event when authentication fails with Stannp" in {
    create404StannpResponse
    withThrowawayConsumerFor(topics.issuedForDelivery.v2) {consumer =>
      val composedPrintEvent = arbitraryComposedPrintEvent
      topics.composedPrint.v1.publishOnce(composedPrintEvent, 10.seconds)

      val failedEvents = consumer.pollFor(noOfEventsExpected = 1)
      failedEvents.size shouldBe 1
      //      failedEvents.foreach(failed => {
      //        failed.reason shouldBe "Error authenticating with the Gateway"
      //        failed.errorCode shouldBe EmailGatewayError
      //      })
    }
  }

  it should "create a failed event when Stannp return a response other than 200" in {

  }

  it should "create a IssuedForDelivery event when Stannp returns a 200 response" in {

  }


  def create404StannpResponse() {
    mockServerClient.reset()
    mockServerClient
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/api/v1/letters/post")
      )
      .respond(
        response("""success":false,"error":"Couldn't download the file""")
          .withStatusCode(404)
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
        response("""success":true,"data":{"id":"219530","pdf":"https:\/\/stannp.blob.core.windows.net\/pdf-production\/2028-150730199132359d79a674efce-b08dd6f579.pdf""")
          .withStatusCode(200)
      )
  }

  def arbitraryComposedPrintEvent: ComposedPrint =
    generate[ComposedPrint].copy(pdfIdentifier = "example.pdf")
}