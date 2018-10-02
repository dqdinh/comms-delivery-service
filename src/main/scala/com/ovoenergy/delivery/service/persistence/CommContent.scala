package com.ovoenergy.delivery.service.persistence

import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

import cats.effect.Sync
import com.ovoenergy.delivery.service.domain.Content
import com.ovoenergy.delivery.service.persistence.S3Repo.{Bucket, Key}
import cats.implicits._
import com.amazonaws.services.s3.AmazonS3URI
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.delivery.config.S3Config

trait CommContent[F[_]] {
  def getEmailContent(composedEmailV4: ComposedEmailV4): F[Content.Email]
  def getSMSContent(composedSMSV4: ComposedSMSV4): F[Content.SMS]
  def getPrintContent(composedPrintV2: ComposedPrintV2): F[Content.Print]
}

object CommContent {

  def isUrl(content: String): Boolean = {
    val url = "https:[\\/]{2}(([0-9a-zA-Z$-_.+!*'(),]+[.])+[0-9a-zA-Z$-_.+!*'(),]+\\/)[0-9a-zA-Z$-_.+!*'(),]+".r
    content match {
      case url(_*) => true
      case _       => false
    }
  }

  def asString(arr: Array[Byte]) = new String(arr, StandardCharsets.UTF_8)

  def apply[F[_]](s3Repo: S3Repo[F], s3Config: S3Config)(implicit F: Sync[F], time: F[ZonedDateTime]) =
    new CommContent[F] {

      private def fetchIfUri(string: String): F[String] = {
        if (isUrl(string)) {
          val s3Uri = new AmazonS3URI(string)
          s3Repo
            .getDocument(Key(s3Uri.getKey), Bucket(s3Uri.getBucket))
            .map(value => new String(value, StandardCharsets.UTF_8))
        } else
          F.pure(string)
      }

      override def getEmailContent(composedEmailV4: ComposedEmailV4): F[Content.Email] = {
        for {
          htmlBodyArr <- fetchIfUri(composedEmailV4.htmlBody)
          subjectArr  <- fetchIfUri(composedEmailV4.subject)
          textBodyArr <- composedEmailV4.textBody.traverse { tb =>
            fetchIfUri(tb)
          }
          content <- Content.Email[F](
            composedEmail = composedEmailV4,
            subject = Content.Subject(subjectArr),
            htmlBody = Content.HtmlBody(htmlBodyArr),
            textBody = textBodyArr.map(Content.TextBody)
          )
        } yield content
      }

      override def getSMSContent(composedSMSV4: ComposedSMSV4): F[Content.SMS] =
        fetchIfUri(composedSMSV4.textBody)
          .map(b => Content.SMS(Content.TextBody(b)))

      override def getPrintContent(composedPrintV2: ComposedPrintV2): F[Content.Print] =
        if (isUrl(composedPrintV2.pdfIdentifier)) {
          val s3Uri = new AmazonS3URI(composedPrintV2.pdfIdentifier)
          s3Repo
            .getDocument(Key(s3Uri.getKey), Bucket(s3Uri.getBucket))
            .map(Content.Print)
        } else
          s3Repo
            .getDocument(Key(composedPrintV2.pdfIdentifier), Bucket(s3Config.printPdfBucketName))
            .map(Content.Print)
    }
}
