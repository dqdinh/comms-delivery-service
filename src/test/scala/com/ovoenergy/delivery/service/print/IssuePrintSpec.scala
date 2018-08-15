package com.ovoenergy.delivery.service.print

import java.time.{Clock, Instant}

import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.{Arbitraries, ErrorCode}
import com.ovoenergy.delivery.service.domain.{Expired, GatewayComm, StannpError}
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.scalacheck.Arbitrary._
import org.scalatest.{FlatSpec, Matchers}

class IssuePrintSpec extends FlatSpec with Matchers with Arbitraries with ArbGenerator {

  implicit val clock = Clock.systemUTC()

  val gatewayComm   = generate[GatewayComm]
  val composedPrint = generate[ComposedPrintV2]
  val deliveryError = {
    StannpError(
      generate[ErrorCode],
      generate[String]
    )
  }
  val pdfDocument = generate[PdfDocument]

  val getPdf                = (_: ComposedPrintV2) => Right(pdfDocument)
  val successfullySendPrint = (_: PdfDocument, _: ComposedPrintV2) => Right(gatewayComm)
  val failedSendPrint       = (_: PdfDocument, _: ComposedPrintV2) => Left(deliveryError)
  val notExpired            = (_: Option[Instant]) => false

  behavior of "PrintDeliveryProcess"

  it should "return gatewayComm when print is successfully sent" in {
    val result = IssuePrint.issue(notExpired, getPdf, successfullySendPrint)(composedPrint)
    result.right.get should be(gatewayComm)
  }

  it should "return delivery error when failed to send print" in {
    val result = IssuePrint.issue(notExpired, getPdf, failedSendPrint)(composedPrint)
    result.left.get should be(deliveryError)
  }

  it should "return expired error when comm has expired" in {
    val result = IssuePrint.issue((_: Option[Instant]) => true, getPdf, successfullySendPrint)(composedPrint)
    result.left.get should be(Expired)
  }

}
