package com.ovoenergy.delivery.service.persistence

import java.time.Instant

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.model.{AmazonDynamoDBException, ResourceNotFoundException}
import com.gu.scanamo._
import com.gu.scanamo.syntax._
import com.ovoenergy.comms.model.UnexpectedDeliveryError
import com.ovoenergy.delivery.service.domain.{DeliveryError, DuplicatedCommDeliveredError, DynamoConnectionError}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.CommRecord
import com.ovoenergy.delivery.service.persistence.DynamoPersistence._

class DynamoPersistence(context: Context) extends LoggingWithMDC {

  def retrieveHashedComm(commRecord: CommRecord): Either[DeliveryError, CommRecord] = {
    try {
      Scanamo.get[CommRecord](context.db)(context.table.name)('commHash -> commRecord.commHash) match {
        case Some(Right(commRecord: CommRecord)) => {
          log.error(s"CommRecord $commRecord was prevented to be delivered repeatedly.")
          Left(DuplicatedCommDeliveredError(commRecord.commHash, UnexpectedDeliveryError))
        }
        case None => Right(commRecord)
      }
    } catch {
      case e: AmazonDynamoDBException =>
        log.error(s"Failed DynamoDB operation: ${e.getMessage}.")
        Left(DynamoConnectionError(UnexpectedDeliveryError))
    }
  }

  def persistHashedComm(commRecord: CommRecord): Either[DeliveryError, Boolean] = {
    val putItemResult = Scanamo.exec(context.db)(context.table.put(commRecord))
    putItemResult.getSdkHttpMetadata.getHttpStatusCode match {
      case 200 => Right(true)
      case _ => {
        log.error(s"Failed to save commRecord $commRecord to DynamoDB.")
        Left(DynamoConnectionError(UnexpectedDeliveryError))
      }
    }
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
