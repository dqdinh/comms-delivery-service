package com.ovoenergy.delivery.service.persistence

import java.io.{BufferedOutputStream, FileOutputStream}
import java.time.{Instant, LocalDate, ZoneOffset}

import com.ovoenergy.comms.model.InternalMetadata
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.config.{AwsConfig, ConstantDelayRetry, S3Config}
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.util.ArbGenerator
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import org.scalatest.{EitherValues, FlatSpec, Matchers}
import org.scalacheck.Shapeless._
import sun.misc.IOUtils

import scala.concurrent.duration._

class S3PdfRepoSpec extends FlatSpec with Matchers with ArbGenerator with EitherValues {

  val retry = ConstantDelayRetry(refineV[Positive](1).right.get, 1.second)
  implicit val awsConfig = AwsConfig("eu-west-1", null, S3Config("dev-ovo-comms-pdfs", retry))

  val s3Context = AwsProvider.getS3Context(false)
  val event = generate[ComposedPrint]

  val commManifest = event.metadata.commManifest.copy(name = "welcomeTest")
  val metadata = event.metadata.copy(
    createdAt = Instant.ofEpochMilli(1507199259856L),
    commManifest = commManifest,
    traceToken = "1234567890")

  println(event)

  val result = S3PdfRepo.getPdfDocument(s3Context)(event.copy(
    pdfIdentifier = "1507199259856-1234567890-8989898989.pdf",
    metadata = metadata,
    internalMetadata = InternalMetadata("8989898989")))

  val x: PdfDocument = result.right.get

  val bos = new BufferedOutputStream(new FileOutputStream("result.pdf"))
  bos.write(x)
  bos.close()
}
