package com.ovoenergy.delivery.service.persistence

import java.time.Instant

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.model.{AmazonDynamoDBException, ResourceNotFoundException}
import com.gu.scanamo._
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.syntax._
import com.ovoenergy.comms.model.UnexpectedDeliveryError
import com.ovoenergy.delivery.config
import com.ovoenergy.delivery.config.DynamoDbConfig
import com.ovoenergy.delivery.service.domain.{DuplicateCommError, DynamoError}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.{CommRecord, Retry}
import com.ovoenergy.delivery.service.persistence.DynamoPersistence._
import com.ovoenergy.delivery.service.util.Retry.{Failed, Succeeded}

class DynamoPersistence(context: Context)(implicit config: DynamoDbConfig) extends LoggingWithMDC {

  def exists(commRecord: CommRecord): Either[DynamoError, Boolean] = {

    val onFailure = { (e: DynamoError) =>
      log.warn(s"Failed to retrieve hashed comm record. ${e.description}")
    }

    val retryResult: Either[Failed[DynamoError], Succeeded[Boolean]] =
      Retry.retry(Retry.constantDelay(config.retryConfig), onFailure) { () =>
        try {
          Scanamo.get[CommRecord](context.db)(context.table.name)('hashedComm -> commRecord.hashedComm) match {
            case Some(Right(_))  => Right(true)
            case None            => Right(false)
            case Some(Left(err)) => Left(DynamoError(UnexpectedDeliveryError))
          }
        } catch {
          case e: AmazonDynamoDBException => {
            log.warn("Failed dynamoDb operation", e)
            Left(DynamoError(UnexpectedDeliveryError))
          }
        }
      }

    retryResult.flatten
  }

  def persistHashedComm(commRecord: CommRecord): Either[DynamoError, Boolean] = {
    val onFailure = { (e: DynamoError) =>
      log.debug(s"Failed to retrieve hashed comm record. ${e.description}")
    }

    val retryResult: Either[Failed[DynamoError], Succeeded[Boolean]] =
      Retry.retry(Retry.constantDelay(config.retryConfig), onFailure) { () =>
        Scanamo.exec(context.db)(context.table.put(commRecord)).getSdkHttpMetadata.getHttpStatusCode match {
          case 200 => Right(true)
          case _ => {
            log.warn(s"Failed to save commRecord $commRecord to DynamoDB.")
            Left(DynamoError(UnexpectedDeliveryError))
          }
        }
      }

    retryResult.flatten
  }
}

object DynamoPersistence {

  implicit val instantDynamoFormat: DynamoFormat[Instant] =
    DynamoFormat.iso[Instant, Long](x => Instant.ofEpochMilli(x))(_.toEpochMilli)

  case class Context(db: AmazonDynamoDBClient, table: Table[CommRecord])

  object Context {
    def apply(db: AmazonDynamoDBClient, tableName: String): Context = {
      Context(
        db,
        Table[CommRecord](tableName)
      )
    }
  }
}
