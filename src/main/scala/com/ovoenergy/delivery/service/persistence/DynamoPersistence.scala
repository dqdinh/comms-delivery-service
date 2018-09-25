package com.ovoenergy.delivery.service.persistence

import java.time.Instant

import cats.effect.Async
import cats.implicits._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.{AmazonDynamoDBException, ProvisionedThroughputExceededException}
import com.gu.scanamo._
import com.gu.scanamo.syntax._
import com.ovoenergy.comms.model.UnexpectedDeliveryError
import com.ovoenergy.delivery.config.DynamoDbConfig
import com.ovoenergy.delivery.service.domain.{CommRecord, DynamoError}
import com.ovoenergy.delivery.service.logging.{Loggable, LoggingWithMDC}
import com.ovoenergy.delivery.service.persistence.DynamoPersistence._
import com.ovoenergy.delivery.service.util.RetryEffect
import fs2.internal.NonFatal

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

class DynamoPersistence[F[_]](dbClient: AmazonDynamoDBAsync)(implicit config: DynamoDbConfig, F: Async[F])
    extends LoggingWithMDC {

  implicit def commRecordLoggable: Loggable[CommRecord] =
    Loggable.instance(record => Map("hashedComm" -> record.hashedComm, "createdAt" -> record.createdAt.toString))

  val commRecordsTable = Table[CommRecord](config.tableNames.commRecord)

  def exists(commRecord: CommRecord): F[Boolean] = {

    val getCommRecord: F[Boolean] =
      try {
        F.async[Boolean] { cb =>
          ScanamoAsync.get[CommRecord](dbClient)(config.tableNames.commRecord)('hashedComm -> commRecord.hashedComm) onComplete {
            case Success(Some(Right(_))) => cb(Right(true))
            case Success(None)           => cb(Right(false))
            case Success(Some(Left(err))) =>
              cb(Left(DynamoError(UnexpectedDeliveryError, s"Failed to fetch CommRecord from dynamodb")))
            case Failure(error) =>
              cb(Left(
                DynamoError(UnexpectedDeliveryError, s"Failed to fetch CommRecord from dynamodb: ${error.getMessage}")))
          }
        }
      } catch {
        case e: AmazonDynamoDBException => {
          F.raiseError(DynamoError(UnexpectedDeliveryError, s"Failed dynamoDb operation: ${e.getMessage}"))
        }
      }

    retry(getCommRecord, commRecord)
  }

  def persistHashedComm(commRecord: CommRecord): F[Unit] = {
    val storeCommRecord = F.async[Unit] { cb =>
      ScanamoAsync.exec(dbClient)(commRecordsTable.put(commRecord)) onComplete {
        case Success(None) => cb(Right(true))
        case Success(Some(Right(_))) => {
          cb(Left(DynamoError(UnexpectedDeliveryError, "Comm hash persisted already exists in DB and is a duplicate.")))
        }
        case Success(Some(Left(_))) => {
          cb(Left(DynamoError(UnexpectedDeliveryError, "Failed to save commRecord $commRecord to DynamoDB.")))
        }
        case Failure(error) => {
          cb(Left(DynamoError(UnexpectedDeliveryError, "Failed to save commRecord $commRecord to DynamoDB.")))
        }
      }
    }

    retry(storeCommRecord, commRecord)
  }

  val retryMaxRetries: Int       = 5
  val retryDelay: FiniteDuration = 5.seconds
  val retryDelayFactor: Double   = 1.5

  def retry[R](fa: F[R], commRecord: CommRecord): F[R] = {
    RetryEffect
      .fixed(retryMaxRetries, retryDelay)(fa, _.isInstanceOf[ProvisionedThroughputExceededException])
      .onError {
        case e: ProvisionedThroughputExceededException =>
          F.delay(logError(commRecord, "Failed to write comm record to DynamoDb, failing the stream", e)) >>
            F.raiseError(e)

        case e: AmazonDynamoDBException =>
          F.raiseError(DynamoError(
            UnexpectedDeliveryError,
            "Failed to write comm record to DynamoDb, skipping the record")) // TODO: Is this logic correct?

        case NonFatal(e) =>
          F.delay(logError(commRecord, "Failed to write comm record to DynamoDb, failing the stream", e)) >>
            F.raiseError(e)
      }
  }
}

object DynamoPersistence {

  implicit val instantDynamoFormat: DynamoFormat[Instant] =
    DynamoFormat.iso[Instant, Long](x => Instant.ofEpochMilli(x))(_.toEpochMilli)

}
