package com.ovoenergy.delivery.service.persistence

import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.ovoenergy.delivery.config.DynamoDbConfig
import org.slf4j.LoggerFactory

object AwsProvider {

  private val log = LoggerFactory.getLogger("AwsClientProvider")

  def dynamoClient(isRunningInLocalDocker: Boolean)(implicit dynamoConfig: DynamoDbConfig): AmazonDynamoDBClient = {
    val region = dynamoConfig.buildRegion
    if (isRunningInLocalDocker) {
      log.warn("Running in local docker")
      System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
      val awsCreds                           = getCreds(isRunningInLocalDocker, region)
      val dynamoClient: AmazonDynamoDBClient = new AmazonDynamoDBClient(awsCreds).withRegion(region)
      dynamoClient.setEndpoint(sys.env("LOCAL_DYNAMO"))
      dynamoClient
    } else {
      val awsCreds = getCreds(isRunningInLocalDocker, region)
      new AmazonDynamoDBClient(awsCreds).withRegion(region)
    }
  }

  private def getCreds(isRunningInLocalDocker: Boolean, region: Regions): AWSCredentialsProvider = {
    if (isRunningInLocalDocker)
      new AWSStaticCredentialsProvider(new BasicAWSCredentials("key", "secret"))
    else
      new AWSCredentialsProviderChain(
        new ContainerCredentialsProvider(),
        new ProfileCredentialsProvider()
      )
  }
}
