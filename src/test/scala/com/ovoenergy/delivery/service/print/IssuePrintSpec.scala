package com.ovoenergy.delivery.service.print

import java.time.{Clock, Instant}

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3Client, S3ClientOptions}
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.delivery.config
import com.ovoenergy.delivery.config.{AwsConfig, ConstantDelayRetry, DynamoDbConfig, S3Config}
import com.ovoenergy.delivery.service.domain.{DeliveryError, Expired, GatewayComm}
import com.ovoenergy.delivery.service.persistence.AwsProvider
import com.ovoenergy.delivery.service.persistence.AwsProvider.S3Context
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.util.ArbGenerator
import com.typesafe.config.ConfigFactory
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalacheck.Shapeless._
import org.scalatest._
import org.scalatest.prop._

import scala.concurrent.duration._

class IssuePrintSpec extends FlatSpec with Matchers with ArbGenerator with GeneratorDrivenPropertyChecks {

  implicit val clock = Clock.systemUTC()

  val gatewayComm   = generate[GatewayComm]
  val composedPrint = generate[ComposedPrintV2]
  val deliveryError = generate[DeliveryError]
  val pdfDocument   = generate[PdfDocument]

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
