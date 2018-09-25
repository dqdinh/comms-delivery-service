package com.ovoenergy.delivery.service.print

import java.time.{Clock, Instant}

import cats.effect.IO
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.comms.model.{Arbitraries, ErrorCode}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.persistence.CommContent
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.scalacheck.Arbitrary._
import org.scalatest.{AsyncFlatSpec, FlatSpec, Matchers}

class IssuePrintSpec extends AsyncFlatSpec with Matchers with Arbitraries with ArbGenerator {
  implicit val clock = Clock.systemUTC()

  val gatewayComm   = generate[GatewayComm]
  val composedPrint = generate[ComposedPrintV2]
  val deliveryError = {
    StannpConnectionError(
      generate[ErrorCode],
      generate[String]
    )
  }
  val pdfDocument = generate[Array[Byte]]

  private val commContent = new CommContent[IO] {
    override def getEmailContent(composedEmailV4: ComposedEmailV4): IO[Content.Email] =
      fail("Incorrect method invoked on CommContent")
    override def getSMSContent(composedSMSV4: ComposedSMSV4): IO[Content.SMS] =
      fail("Incorrect method invoked on CommContent")
    override def getPrintContent(composedPrintV2: ComposedPrintV2): IO[Content.Print] = IO(Content.Print(pdfDocument))
  }
  val getPdf                = (_: ComposedPrintV2) => IO(pdfDocument)
  val successfullySendPrint = (_: Content.Print, _: ComposedPrintV2) => IO(gatewayComm)
  val failedSendPrint: (Content.Print, ComposedPrintV2) => IO[GatewayComm] = (_: Content.Print, _: ComposedPrintV2) =>
    IO.raiseError(deliveryError)

  val notExpired = (_: Option[Instant]) => false

  behavior of "PrintDeliveryProcess"

  it should "return gatewayComm when print is successfully sent" in {
    IssuePrint
      .issue[IO](notExpired, commContent, successfullySendPrint)
      .apply(composedPrint)
      .unsafeToFuture()
      .map(_ shouldBe gatewayComm)

  }

  it should "return delivery error when failed to send print" in {
    IssuePrint
      .issue(notExpired, commContent, failedSendPrint)
      .apply(composedPrint)
      .unsafeToFuture()
      .failed
      .map(_ shouldBe deliveryError)
  }

  it should "return expired error when comm has expired" in {
    IssuePrint
      .issue[IO]((_: Option[Instant]) => true, commContent, successfullySendPrint)
      .apply(composedPrint)
      .unsafeToFuture()
      .failed
      .map(_ shouldBe Expired)
  }
}
