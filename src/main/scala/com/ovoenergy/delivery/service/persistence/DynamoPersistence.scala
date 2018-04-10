package com.ovoenergy.delivery.service.persistence

import java.time.Instant

import cats.effect.Async
import cats.syntax.all._
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

class DynamoPersistence(context: Context)(implicit config: DynamoDbConfig) extends LoggingWithMDC {

  implicit def commRecordLoggable: Loggable[CommRecord] =
    Loggable.instance(record => Map("hashedComm" -> record.hashedComm, "createdAt" -> record.createdAt.toString))

  def exists[F[_]: Async](commRecord: CommRecord): F[Either[DynamoError, Boolean]] = {

    val getCommRecord: F[Either[DynamoError, Boolean]] =
      try {
        Async[F].async[Either[DynamoError, Boolean]] { cb =>
          ScanamoAsync.get[CommRecord](context.db)(context.table.name)('hashedComm -> commRecord.hashedComm) onComplete {
            case Success(Some(Right(_)))  => cb(Right(Right(true)))
            case Success(None)            => cb(Right(Right(false)))
            case Success(Some(Left(err))) => cb(Right(Left(DynamoError(UnexpectedDeliveryError))))
            case Failure(error)           => cb(Right(Left(DynamoError(UnexpectedDeliveryError))))
          }
        }
      } catch {
        case e: AmazonDynamoDBException => {
          log.warn("Failed dynamoDb operation", e)
          Async[F].pure(Left(DynamoError(UnexpectedDeliveryError)))
        }
      }

    retry(getCommRecord, commRecord)
  }

  def persistHashedComm[F[_]: Async](commRecord: CommRecord): F[Either[DynamoError, Boolean]] = {

    println("in persist")
    val storeCommRecord = Async[F].async[Either[DynamoError, Boolean]] { cb =>
      ScanamoAsync.exec(context.db)(context.table.put(commRecord)) onComplete {
        case Success(None) => cb(Right(Right(true)))
        case Success(Some(Right(_))) => {
          log.error(s"Comm hash persisted already exists in DB and is a duplicate.")
          cb(Right(Left(DynamoError(UnexpectedDeliveryError))))
        }
        case Success(Some(Left(_))) => {
          log.warn(s"Failed to save commRecord $commRecord to DynamoDB.")
          cb(Right(Left(DynamoError(UnexpectedDeliveryError))))
        }
        case Failure(error) => {
          log.warn(s"Failed to save commRecord $commRecord to DynamoDB.")
          cb(Right(Left(DynamoError(UnexpectedDeliveryError))))
        }
      }
    }

    val res = retry(storeCommRecord, commRecord)
    println("Return from persist")
    res
  }

  val retryMaxRetries: Int       = 5
  val retryDelay: FiniteDuration = 5.seconds
  val retryDelayFactor: Double   = 1.5

  def retry[F[_]: Async](fa: F[Either[DynamoError, Boolean]],
                         commRecord: CommRecord): F[Either[DynamoError, Boolean]] = {
    RetryEffect.fixed(retryMaxRetries, retryDelay)(
      fa,
      _.isInstanceOf[ProvisionedThroughputExceededException]) recoverWith {
      case e: ProvisionedThroughputExceededException =>
        logError(commRecord, "Failed to write comm record to DynamoDb, failing the stream", e)
        Async[F].raiseError(e)

      case e: AmazonDynamoDBException =>
        logWarn(commRecord, "Failed to write comm record to DynamoDb, skipping the record", e)
        Async[F].pure(Left(DynamoError(UnexpectedDeliveryError)))

      case NonFatal(e) =>
        logError(commRecord, "Failed to write comm record to DynamoDb, failing the stream", e)
        Async[F].raiseError(e)
    }
  }
}

object DynamoPersistence {

  implicit val instantDynamoFormat: DynamoFormat[Instant] =
    DynamoFormat.iso[Instant, Long](x => Instant.ofEpochMilli(x))(_.toEpochMilli)

  case class Context(db: AmazonDynamoDBAsync, table: Table[CommRecord])

  object Context {
    def apply(db: AmazonDynamoDBAsync, tableName: String): Context = {
      Context(
        db,
        Table[CommRecord](tableName)
      )
    }
  }
}
