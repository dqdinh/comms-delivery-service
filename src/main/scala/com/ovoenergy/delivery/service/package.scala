package com.ovoenergy.delivery

import com.amazonaws.regions.Regions
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

import scala.concurrent.duration.FiniteDuration

package object config {

  case class ApplicationConfig(mailgun: MailgunAppConfig,
                               email: EmailAppConfig,
                               sms: SmsAppConfig,
                               twilio: TwilioAppConfig,
                               kafka: KafkaAppConfig,
                               stannp: StannpConfig,
                               aws: AwsConfig)
  case class EmailAppConfig(whitelist: String, blacklist: List[String])
  case class SmsAppConfig(whitelist: List[String], blacklist: List[String])
  case class KafkaAppConfig(retry: ExponentialDelayRetry)
  case class TwilioServiceSids(ovo: String,
                               boost: String,
                               lumo: String,
                               corgi: String,
                               vnet: String,
                               energySw: String,
                               fairerpower: String,
                               peterboroughEnergy: String,
                               southendEnergy: String)
  case class TwilioAppConfig(accountSid: String,
                             authToken: String,
                             serviceSids: TwilioServiceSids,
                             apiUrl: String,
                             retry: ConstantDelayRetry)
  case class MailgunAppConfig(host: String, apiKey: String, domain: String, retry: ConstantDelayRetry)
  case class ConstantDelayRetry(attempts: Int Refined Positive, interval: FiniteDuration)
  case class ExponentialDelayRetry(attempts: Int Refined Positive, initialInterval: FiniteDuration, exponent: Double)
  case class TableNames(commRecord: String, templateSummary: String)
  case class DynamoDbConfig(retryConfig: ConstantDelayRetry, tableNames: TableNames)
  case class StannpConfig(url: String,
                          apiKey: String,
                          password: String,
                          country: String,
                          test: Boolean,
                          retry: ConstantDelayRetry)
  case class S3Config(printPdfBucketName: String, retryConfig: ConstantDelayRetry)
  case class AwsConfig(region: String, dynamo: DynamoDbConfig, s3: S3Config) {
    def buildRegion = Regions.fromName(region)
  }

  implicit def configuration2Aws(implicit configuration: ApplicationConfig): AwsConfig = configuration.aws
  implicit def configuration2S3(implicit configuration: ApplicationConfig): S3Config   = configuration.aws.s3
  implicit def configuration2Dynamo(implicit configuration: ApplicationConfig): DynamoDbConfig =
    configuration.aws.dynamo
  implicit def configuration2mailgun(implicit configuration: ApplicationConfig): MailgunAppConfig =
    configuration.mailgun
  implicit def configuration2Twilio(implicit configuration: ApplicationConfig): TwilioAppConfig = configuration.twilio
  implicit def configuration2Kafka(implicit configuration: ApplicationConfig): KafkaAppConfig   = configuration.kafka
  implicit def configuration2Email(implicit configuration: ApplicationConfig): EmailAppConfig   = configuration.email
  implicit def configuration2Sms(implicit configuration: ApplicationConfig): SmsAppConfig       = configuration.sms
  implicit def configuration2Stannp(implicit configuration: ApplicationConfig): StannpConfig    = configuration.stannp
  implicit def configuration3TableNames(implicit configuration: ApplicationConfig): TableNames =
    configuration.aws.dynamo.tableNames
  implicit def configuration3Retry(implicit configuration: ApplicationConfig): ConstantDelayRetry =
    configuration.aws.dynamo.retryConfig
}
