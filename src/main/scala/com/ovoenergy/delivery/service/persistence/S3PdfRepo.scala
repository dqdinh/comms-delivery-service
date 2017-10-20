package com.ovoenergy.delivery.service.persistence

import java.io.IOException

import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.util.IOUtils
import com.ovoenergy.comms.model.TemplateDownloadFailed
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.service.domain.{AmazonS3Error, DeliveryError, S3ConnectionError}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.persistence.AwsProvider.S3Context
import com.ovoenergy.delivery.service.print.IssuePrint.PdfDocument
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.{Failed, Succeeded}

object S3PdfRepo extends LoggingWithMDC {

  def getPdfDocument(s3Context: S3Context)(composedPrint: ComposedPrint): Either[DeliveryError, PdfDocument] = {

    val s3Config = s3Context.s3Config
    val s3Client = s3Context.s3Client

    val onFailure = { (e: DeliveryError) =>
      log.warn(s"Failed to retrieve pdf document. ${e.description}")
    }

    val result: Either[Failed[DeliveryError], Succeeded[PdfDocument]] =
      Retry.retry[DeliveryError, PdfDocument](Retry.constantDelay(s3Config.retryConfig), onFailure) { () =>
        try {
          val pdf = s3Client.getObject(s3Config.printPdfBucketName, composedPrint.pdfIdentifier)
          Right(IOUtils.toByteArray(pdf.getObjectContent))
        } catch {
          case e: IOException => {
            log.warn("Failed to connect to S3", e)
            Left(S3ConnectionError(TemplateDownloadFailed, s3Config.printPdfBucketName))
          }
          case e: AmazonS3Exception => {
            log.warn(s"Key ${composedPrint.pdfIdentifier} does not exist in bucket ${s3Config.printPdfBucketName}", e)
            Left(AmazonS3Error(TemplateDownloadFailed, s3Config.printPdfBucketName, composedPrint.pdfIdentifier))
          }
        }
      }

    result.flatten
  }
}
