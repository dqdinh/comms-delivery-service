package com.ovoenergy.delivery.service.persistence

import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

import cats.effect.IO
import com.ovoenergy.comms.model.Arbitraries
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.delivery.service.util.{ArbGenerator, Retry}
import org.scalacheck.Arbitrary
import org.scalatest.{AsyncFlatSpec, Matchers, OptionValues}
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.delivery.config.{ConstantDelayRetry, S3Config}
import com.ovoenergy.delivery.service.ConfigLoader
import com.ovoenergy.delivery.service.domain.Content
import com.ovoenergy.delivery.service.persistence.S3Repo.Bucket
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._

import scala.concurrent.duration._

class CommContentSpec extends AsyncFlatSpec with Matchers with Arbitraries with ArbGenerator with OptionValues {

  behavior of "getEmailContent"

  implicit val time = IO(ZonedDateTime.now())
  val s3Config      = S3Config("dev-ovo-comms-pdfs", ConstantDelayRetry(3, 3 seconds))

  it should "fetch the body of a comm from s3 if the content is a link" in {
    val composedEmailUrls: Arbitrary[ComposedEmailV4] = {
      Arbitrary(
        for {
          metadata         <- arbMetadataV3.arbitrary
          internalMetadata <- arbInternalMetadata.arbitrary
          sender           <- arbString.arbitrary
          recipient        <- arbString.arbitrary
          hashedComm       <- arbString.arbitrary
          randomString     <- genNonEmptyString
        } yield
          ComposedEmailV4(
            metadata,
            internalMetadata,
            sender,
            recipient,
            s"https://bucketName.s3-eu-west-2.amazonaws.com/${randomString}/subject",
            s"https://bucketName.s3-eu-west-2.amazonaws.com/${randomString}/htmlBody",
            hashedComm,
            None,
            None
          ))
    }

    val composedEmailV4 = generate[ComposedEmailV4](composedEmailUrls)
    val expectedResultBytes: Map[String, Array[Byte]] = Map(
      composedEmailV4.htmlBody -> "htmlBody".getBytes(StandardCharsets.UTF_8),
      composedEmailV4.subject  -> "subject".getBytes(StandardCharsets.UTF_8)
    ) ++ composedEmailV4.textBody.map(tb => Map(tb -> "textBody".getBytes(StandardCharsets.UTF_8))).getOrElse(Map.empty)

    val expectedResultStrings = expectedResultBytes.mapValues(new String(_, StandardCharsets.UTF_8))

    val s3Repo = new S3Repo[IO] {
      override def getDocument(key: S3Repo.Key, bucket: Bucket): IO[Array[Byte]] =
        IO(expectedResultBytes.get(s"https://bucketName.s3-eu-west-2.amazonaws.com/${key.value}").value)
    }

    val commContent = CommContent.apply[IO](s3Repo, s3Config)
    commContent
      .getEmailContent(composedEmailV4)
      .unsafeToFuture()
      .map { r =>
        val expectedSubject  = expectedResultStrings.get(composedEmailV4.subject).value
        val expectedHtmlBody = expectedResultStrings.get(composedEmailV4.htmlBody).value
        val expectedTextBody = composedEmailV4.textBody.map(tb => expectedResultStrings.get(tb).value)

        r.subject.value shouldBe expectedSubject
        r.htmlBody.value shouldBe expectedHtmlBody
        r.textBody.map(_.value) shouldBe expectedTextBody
      }
  }

  it should "pass through the comm body if the content is passed in the event" in {
    val composedEmailV4 = generate[ComposedEmailV4]
    val s3Repo = new S3Repo[IO] {
      override def getDocument(key: S3Repo.Key, bucket: Bucket): IO[Array[Byte]] =
        IO.raiseError(fail("S3 invoked where comm contents were not a link"))
    }

    val commContent = CommContent.apply[IO](s3Repo, s3Config)
    commContent
      .getEmailContent(composedEmailV4)
      .unsafeToFuture()
      .map { content =>
        content.subject.value shouldBe composedEmailV4.subject
        content.htmlBody.value shouldBe composedEmailV4.htmlBody
        content.textBody.map(_.value) shouldBe composedEmailV4.textBody
      }
  }

  behavior of "getSmsContent"

  it should "fetch the body of a comm from s3 if the content is a link" in {
    val composedSMSWithUrls: Arbitrary[ComposedSMSV4] = {
      Arbitrary(
        for {
          metadata         <- arbMetadataV3.arbitrary
          internalMetadata <- arbInternalMetadata.arbitrary
          recipient        <- arbString.arbitrary
          hashedComm       <- arbString.arbitrary
          randomString     <- genNonEmptyString
        } yield
          ComposedSMSV4(
            metadata,
            internalMetadata,
            recipient,
            s"https://s3.eu-west-2.amazonaws.com/${randomString}/subject",
            hashedComm,
            None
          ))
    }

    val composedSMS = generate[ComposedSMSV4](composedSMSWithUrls)

    val expectedResultBytes  = "textBody".getBytes(StandardCharsets.UTF_8)
    val expectedResultString = new String(expectedResultBytes, StandardCharsets.UTF_8)

    val s3Repo = new S3Repo[IO] {
      override def getDocument(key: S3Repo.Key, bucket: Bucket): IO[Array[Byte]] = IO(expectedResultBytes)
    }

    val commContent = CommContent.apply[IO](s3Repo, s3Config)
    commContent
      .getSMSContent(composedSMS)
      .unsafeToFuture()
      .map { r: Content.SMS =>
        r.textBody.value shouldBe expectedResultString
      }
  }

  it should "pass through the comm body if the content is passed in the event" in {
    val composedSMS = generate[ComposedSMSV4]
    val s3Repo = new S3Repo[IO] {
      override def getDocument(key: S3Repo.Key, bucket: Bucket): IO[Array[Byte]] =
        IO.raiseError(fail("S3 invoked where comm contents were not a link"))
    }

    CommContent
      .apply[IO](s3Repo, s3Config)
      .getSMSContent(composedSMS)
      .unsafeToFuture()
      .map(_.textBody.value shouldBe composedSMS.textBody)
  }

  behavior of "getPrintContent"

  it should "fetch the body of a comm from S3" in {
    val composedPrint  = generate[ComposedPrintV2]
    val expectedResult = generate[Array[Byte]]

    val s3Repo = new S3Repo[IO] {
      override def getDocument(key: S3Repo.Key, bucket: Bucket): IO[Array[Byte]] =
        IO(expectedResult)
    }

    CommContent
      .apply[IO](s3Repo, s3Config)
      .getPrintContent(composedPrint)
      .unsafeToFuture
      .map(_.value shouldBe expectedResult)
  }
}
