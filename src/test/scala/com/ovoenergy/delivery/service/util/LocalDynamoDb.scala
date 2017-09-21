package com.ovoenergy.delivery.service.util

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.dynamodbv2._
import com.amazonaws.services.dynamodbv2.model._

import scala.collection.JavaConverters._

object LocalDynamoDb {
  def client(endPoint: String = s"http://localhost:8000") = {
    val c = new AmazonDynamoDBClient(new AWSStaticCredentialsProvider(new BasicAWSCredentials("key", "secret")))
    c.setEndpoint(endPoint)
    c
  }

  def doesTableExist(client: AmazonDynamoDB, tableName: String) = {
    client.listTables().getTableNames.asScala.contains(tableName)
  }

  def createTable(client: AmazonDynamoDB)(tableName: String)(attributes: (Symbol, ScalarAttributeType)*) = {
    client.createTable(
      attributeDefinitions(attributes),
      tableName,
      keySchema(attributes),
      arbitraryThroughputThatIsIgnoredByDynamoDBLocal
    )
  }

  def withTable[T](client: AmazonDynamoDB)(tableName: String)(attributeDefinitions: (Symbol, ScalarAttributeType)*)(
      thunk: => T
  ): T = {
    createTable(client)(tableName)(attributeDefinitions: _*)
    val res = try {
      thunk
    } finally {
      client.deleteTable(tableName)
      ()
    }
    res
  }

  def usingTable[T](client: AmazonDynamoDB)(tableName: String)(attributeDefinitions: (Symbol, ScalarAttributeType)*)(
      thunk: => T
  ): Unit = {
    withTable(client)(tableName)(attributeDefinitions: _*)(thunk)
    ()
  }

  private def keySchema(attributes: Seq[(Symbol, ScalarAttributeType)]) = {
    val hashKeyWithType :: rangeKeyWithType = attributes.toList
    val keySchemas                          = hashKeyWithType._1 -> KeyType.HASH :: rangeKeyWithType.map(_._1 -> KeyType.RANGE)
    keySchemas.map { case (symbol, keyType) => new KeySchemaElement(symbol.name, keyType) }.asJava
  }

  private def attributeDefinitions(attributes: Seq[(Symbol, ScalarAttributeType)]) = {
    attributes.map { case (symbol, attributeType) => new AttributeDefinition(symbol.name, attributeType) }.asJava
  }

  private val arbitraryThroughputThatIsIgnoredByDynamoDBLocal = new ProvisionedThroughput(1L, 1L)
}
