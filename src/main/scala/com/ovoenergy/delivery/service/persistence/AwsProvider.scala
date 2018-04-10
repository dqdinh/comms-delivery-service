package com.ovoenergy.delivery.service.persistence

import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDBAsync, AmazonDynamoDBAsyncClientBuilder, AmazonDynamoDBClient}
import com.amazonaws.services.s3.AmazonS3Client
import com.ovoenergy.delivery.config.{AwsConfig, S3Config}
import org.slf4j.LoggerFactory

object AwsProvider {

  private val log = LoggerFactory.getLogger("AwsClientProvider")

  def dynamoClient(isRunningInLocalDocker: Boolean)(implicit awsConfig: AwsConfig): AmazonDynamoDBAsync = {
    val region = awsConfig.buildRegion
    if (isRunningInLocalDocker) {
      log.warn("Running in local docker")
      System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
      AmazonDynamoDBAsyncClientBuilder
        .standard()
        .withEndpointConfiguration(new EndpointConfiguration(sys.env("LOCAL_DYNAMO"), region.getName))
        .build()
    } else {
      val awsCreds = getCreds(isRunningInLocalDocker, region)
      AmazonDynamoDBAsyncClientBuilder
        .standard()
        .withCredentials(awsCreds)
        .withRegion(region)
        .build()
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

  case class S3Context(s3Client: AmazonS3Client, s3Config: S3Config)

  def getS3Context(isRunningInLocalDocker: Boolean)(implicit awsConfig: AwsConfig) = {

    val s3Client: AmazonS3Client = {
      val region = awsConfig.buildRegion
      if (isRunningInLocalDocker) {
        System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")
        val awsCreds = new AWSStaticCredentialsProvider(new BasicAWSCredentials("key", "secret"))
        new AmazonS3Client(awsCreds).withRegion(region)
      } else {
        val awsCreds = getCreds(isRunningInLocalDocker, region)
        new AmazonS3Client(awsCreds).withRegion(region)
      }
    }

    S3Context(s3Client, awsConfig.s3)
  }

}
