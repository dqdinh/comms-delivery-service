package com.ovoenergy.delivery.service.print.stannp

import java.nio.file.{Files, Paths}
import java.time.{Clock, OffsetDateTime, ZoneId}

import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.config.{AwsConfig, ConstantDelayRetry, S3Config, StannpConfig}
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.persistence.AwsProvider.S3Context
import com.ovoenergy.delivery.service.persistence.{AwsProvider, S3PdfRepo}
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.circe.generic.auto._
import io.circe.generic.extras.semiauto.deriveEnumerationEncoder
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

import scala.concurrent.duration._
import scala.io.Source

object StannpTest extends App {

  val retry = ConstantDelayRetry(refineV[Positive](1).right.get, 1.second)
  implicit val awsConfig = AwsConfig("eu-west-1", null, S3Config("dev-ovo-comms-pdfs", retry))
  val s3Context = AwsProvider.getS3Context(false)
  val event = ComposedPrint(null, null, "ThisIsSparta", null, null)

  val result = S3PdfRepo.getPdfDocument(s3Context)(event)

  println(result)

//  val dateTime = OffsetDateTime.now(ZoneId.of("UTC"))
//
//  val url = "https://dash.stannp.com/api/v1/letters/post"
//  val test = "true"
//  val country = "GB"
//  val apiKey = ""
//  val password = ""
//  val retry = ConstantDelayRetry(refineV[Positive](1).right.get, 1.second)
//
//  implicit val clock = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))
//  implicit val stannpConfig = StannpConfig(url, apiKey, password, country, test, retry)
//
//  val httpClient = HttpClient.apply(_)
//
//  val byteArray: PdfDocument = Files.readAllBytes(Paths.get("testIntegration.pdf"))
//
//  val event = ComposedPrint(null, null, "ThisIsSparta", null, null)
//
//  val response = StannpClient.send(httpClient).apply(byteArray, event)
//
//  println(response)
}
