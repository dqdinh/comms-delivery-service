package com.ovoenergy.delivery.service.persistence

import java.io.IOException
import java.time.{LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter

import com.amazonaws.util.IOUtils
import com.ovoenergy.comms.model.UnexpectedDeliveryError
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.service.domain.{DeliveryError, S3Error}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.persistence.AwsProvider.S3Context
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.{Failed, Succeeded}

object S3PdfRepo extends LoggingWithMDC {

  def getPdfDocument(s3Context: S3Context)(composedPrint: ComposedPrint): Either[DeliveryError, PdfDocument] = {

    val s3Config = s3Context.s3Config
    val s3Client = s3Context.s3Client

    val pdf = s3Client
      .getObject(s3Config.printPdfBucketName, buildKey(composedPrint))

    val onFailure = { (e: S3Error) =>
      log.warn(s"Failed to retrieve pdf document. ${e.description}")
    }

    val result: Either[Failed[S3Error], Succeeded[PdfDocument]] =
      Retry.retry[S3Error, PdfDocument](Retry.constantDelay(s3Config.retryConfig), onFailure) { () =>
        try{
          Right(IOUtils.toByteArray(pdf.getObjectContent))
        } catch {
          case e: IOException => {
            log.warn("Failed to connect to S3", e)
            Left(S3Error(UnexpectedDeliveryError, s3Config.printPdfBucketName)) // TODO: error code???
          }
        }
      }

    result flatten
  }

  private def buildKey(composedPrint: ComposedPrint): String = {
    val commName = composedPrint.metadata.commManifest.name
    val createdAt = composedPrint.metadata.createdAt
    val tt = composedPrint.metadata.traceToken
    val itt = composedPrint.internalMetadata.internalTraceToken

    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val dateOfCreation = LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()).format(dateFormatter)

    s"$commName/$dateOfCreation/${createdAt.toEpochMilli}-$tt-$itt.pdf"
  }
}
