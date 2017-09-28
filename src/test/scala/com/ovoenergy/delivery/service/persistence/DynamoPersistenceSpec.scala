package com.ovoenergy.delivery.service.persistence

import java.time.Instant

import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType
import com.ovoenergy.comms.model.{CommManifest, Service, UnexpectedDeliveryError}
import com.ovoenergy.delivery.service.domain.DuplicateCommError
import com.ovoenergy.delivery.service.persistence.DynamoPersistence.Context
import com.ovoenergy.delivery.service.util.{CommRecord, LocalDynamoDb}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

class DynamoPersistenceSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  val keyString         = "asdfghjkl"
  val now               = Instant.now
  val localDynamo       = LocalDynamoDb.client()
  val tableName         = "commRecord"
  val context           = Context(localDynamo, tableName)
  val dynamoPersistence = new DynamoPersistence(context)

  val commRecords = List(
    CommRecord("54ter54ertt34tgr", now.minusSeconds(10)),
    CommRecord("345ertfdey6u5yetwfwg", now.minusSeconds(5)),
    CommRecord(keyString, now)
  )

  override def beforeAll() = {
    LocalDynamoDb.withTable(localDynamo)(tableName)('commHash -> ScalarAttributeType.S) {
      commRecords.foreach(commRecord => {
        dynamoPersistence.persistHashedComm(commRecord) shouldBe Right(true)
      })
    }
  }

  it should "retrieve commRecord which is already stored at Dynamo" in {

    LocalDynamoDb.withTable(localDynamo)(tableName)('commHash -> ScalarAttributeType.S) {
      commRecords.foreach(dynamoPersistence.persistHashedComm)
      dynamoPersistence.exists(CommRecord(keyString, now)) shouldBe Left(
        DuplicateCommError(keyString, UnexpectedDeliveryError))
    }

  }

  it should "return Right(commRecord) if the call is successful but the record does not exist" in {
    LocalDynamoDb.withTable(localDynamo)(tableName)('commHash -> ScalarAttributeType.S) {
      commRecords.foreach(dynamoPersistence.persistHashedComm)
      dynamoPersistence.exists(CommRecord("nonExistingKey", now)) shouldBe Right(CommRecord("nonExistingKey", now))
    }
  }

}
