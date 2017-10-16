package com.ovoenergy.delivery.service.persistence

import java.time.Instant

import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType
import com.ovoenergy.delivery.config.{ConstantDelayRetry, DynamoDbConfig}
import com.ovoenergy.delivery.service.domain.CommRecord
import com.ovoenergy.delivery.service.persistence.DynamoPersistence.Context
import com.ovoenergy.delivery.service.util.LocalDynamoDb

import scala.concurrent.duration._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

class DynamoPersistenceSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  val keyString   = "asdfghjkl"
  val now         = Instant.now
  val localDynamo = LocalDynamoDb.client()
  val tableName   = "commRecord"
  val context     = Context(localDynamo, tableName)
  val dynamoPersistence = new DynamoPersistence(context)(
    DynamoDbConfig(ConstantDelayRetry(refineV[Positive](1).right.get, 1.second), "eu-west-1"))

  val commRecords = List(
    CommRecord("54ter54ertt34tgr", now.minusSeconds(10)),
    CommRecord("345ertfdey6u5yetwfwg", now.minusSeconds(5)),
    CommRecord(keyString, now)
  )

  override def beforeAll() = {
    LocalDynamoDb.withTable(localDynamo)(tableName)('hashedComm -> ScalarAttributeType.S) {
      commRecords.foreach(commRecord => {
        dynamoPersistence.persistHashedComm(commRecord) shouldBe Right(true)
      })
    }
  }

  it should "retrieve commRecord which is already stored at Dynamo" in {

    LocalDynamoDb.withTable(localDynamo)(tableName)('hashedComm -> ScalarAttributeType.S) {
      commRecords.foreach(dynamoPersistence.persistHashedComm)
      dynamoPersistence.exists(CommRecord(keyString, now.plusSeconds(10))) shouldBe Right(true)
    }

  }

  it should "return Right(commRecord) if the call is successful but the record does not exist" in {
    LocalDynamoDb.withTable(localDynamo)(tableName)('hashedComm -> ScalarAttributeType.S) {
      commRecords.foreach(dynamoPersistence.persistHashedComm)
      dynamoPersistence.exists(CommRecord("nonExistingKey", now)) shouldBe Right(false)
    }
  }

}
