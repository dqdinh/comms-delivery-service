package com.ovoenergy.delivery.service.persistence

import java.time.Instant

import cats.effect.IO
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType
import com.ovoenergy.delivery.config.{ConstantDelayRetry, DynamoDbConfig, TableNames}
import com.ovoenergy.delivery.service.{ConfigLoader, domain}
import com.ovoenergy.delivery.service.domain.CommRecord
import com.ovoenergy.delivery.service.util.LocalDynamoDb

import scala.concurrent.duration._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import cats.implicits._
import com.ovoenergy.delivery.config

class DynamoPersistenceSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  val ec             = scala.concurrent.ExecutionContext.global
  implicit val timer = IO.timer(ec)
  implicit val ctx   = IO.contextShift(ec)

  implicit val dynamConf: config.DynamoDbConfig =
    DynamoDbConfig(
      ConstantDelayRetry(refineV[Positive](1).right.get, 1.second),
      TableNames("commRecord", "templateSummaryTable")
    )

  val keyString   = "asdfghjkl"
  val now         = Instant.now
  val localDynamo = LocalDynamoDb.client()
  val tableName   = "commRecord"
  val dynamoPersistence =
    new DynamoPersistence[IO](localDynamo)

  val commRecords = List(
    CommRecord("54ter54ertt34tgr", now.minusSeconds(10)),
    CommRecord("345ertfdey6u5yetwfwg", now.minusSeconds(5)),
    CommRecord(keyString, now)
  )

  override def beforeAll() = {
    LocalDynamoDb.withTable(localDynamo)(tableName)('hashedComm -> ScalarAttributeType.S) {
      commRecords.foreach(commRecord => {
        dynamoPersistence.persistHashedComm(commRecord).unsafeRunSync() should be(())
      })
    }
  }

  it should "retrieve commRecord which is already stored at Dynamo" in {
    LocalDynamoDb.withTable(localDynamo)(tableName)('hashedComm -> ScalarAttributeType.S) {
      commRecords.map(dynamoPersistence.persistHashedComm).sequence.unsafeRunSync()
      dynamoPersistence.exists(CommRecord(keyString, now.plusSeconds(10))).unsafeRunSync() shouldBe true
    }

  }

  it should "return Right(commRecord) if the call is successful but the record does not exist" in {
    LocalDynamoDb.withTable(localDynamo)(tableName)('hashedComm -> ScalarAttributeType.S) {
      dynamoPersistence.exists(CommRecord("nonExistingKey", now)).unsafeRunSync() shouldBe false
    }
  }

}
