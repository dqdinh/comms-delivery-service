package com.ovoenergy.delivery.service.persistence

import java.io.IOException

import cats.effect.{Async, Sync}
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.util.IOUtils
import com.ovoenergy.comms.model.UnexpectedDeliveryError
import com.ovoenergy.delivery.service.domain.{AmazonS3Error, DeliveryError, S3ConnectionError}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.persistence.AwsProvider.S3Context
import com.ovoenergy.delivery.service.util.Retry
import cats.implicits._
import com.ovoenergy.delivery.service.persistence.S3Repo.{Bucket, Key}

trait S3Repo[F[_]] {
  def getDocument(key: Key, bucket: Bucket): F[Array[Byte]]
}

object S3Repo extends LoggingWithMDC {

  // TODO: Change to use https://github.com/ovotech/comms-aws

  def apply[F[_]](s3Context: S3Context)(implicit F: Async[F]) = new S3Repo[F]() {
    def getDocument(key: Key, bucket: Bucket): F[Array[Byte]] = {
      val s3Config = s3Context.s3Config
      val s3Client = s3Context.s3Client

      val onFailure = { (e: DeliveryError) =>
        log.warn(s"Failed to retrieve communication content with key ${key.value}. ${e.description}")
      }

      F.delay {
        Retry
          .retry[DeliveryError, Array[Byte]](Retry.constantDelay(s3Config.retryConfig), onFailure) { () =>
            try {
              val document = s3Client.getObject(bucket.value, key.value)
              Right(IOUtils.toByteArray(document.getObjectContent))
            } catch {
              case e: IOException => {
                Left(
                  S3ConnectionError(UnexpectedDeliveryError, bucket.value, s"Failed to connect to S3: ${e.getMessage}"))
              }
              case e: AmazonS3Exception => {
                Left(
                  AmazonS3Error(UnexpectedDeliveryError,
                                bucket.value,
                                key.value,
                                s"Key ${key.value} does not exist in bucket ${bucket.value}"))
              }
            }
          }
          .leftMap(_.finalFailure: Throwable)
          .map(_.result)
      }.rethrow
    }
  }
  case class Key(value: String)
  case class Bucket(value: String)
}
