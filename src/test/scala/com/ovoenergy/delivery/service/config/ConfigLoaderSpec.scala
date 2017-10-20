package com.ovoenergy.delivery.service.config

import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.delivery.config.{
  AwsConfig,
  ConstantDelayRetry,
  DynamoDbConfig,
  EmailAppConfig,
  ExponentialDelayRetry,
  KafkaAppConfig,
  MailgunAppConfig,
  S3Config,
  SmsAppConfig,
  StannpConfig,
  TwilioAppConfig
}
import com.ovoenergy.delivery.service.ConfigLoader
import com.typesafe.config.ConfigFactory
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

class ConfigLoaderSpec extends FlatSpec with Matchers {
  behavior of "configLoader"

  val failuresOrConfig = ConfigLoader.applicationConfig("test.conf")

  val expectedTwilioConfig =
    TwilioAppConfig("test_account_SIIID",
                    "test_auth_TOKEEEN",
                    "test_service_SIIID",
                    "twilio_url_test",
                    ConstantDelayRetry(refineV[Positive](5).right.get, 1.second))

  val expectedKafkaConfig =
    KafkaAppConfig(ExponentialDelayRetry(refineV[Positive](6).right.get, 2.second, 2))

  val expectedMailgunConfig =
    MailgunAppConfig(
      "http://mailgun.com",
      "mykey123",
      "myDomain",
      ConstantDelayRetry(refineV[Positive](7).right.get, 3.second)
    )

  val expectedEmailConfig = EmailAppConfig(".*@ovoenergy.com", List("some@email.com"))
  val expectedSmsConfig   = SmsAppConfig(List.empty, List.empty)

  val testRetry = ConstantDelayRetry(refineV[Positive](5).right.get, 1.second)

  val expectedStannpConfig =
    StannpConfig("https://dash.stannp.com/api/v1/letters/post", "apiKeee", "pass", "GB", "true", testRetry)

  val expectedAwsConfig = AwsConfig("eu-west-1", DynamoDbConfig(testRetry), S3Config("dev-ovo-comms-pdfs", testRetry))

  it should "load the configuration file" in {

    failuresOrConfig shouldBe 'right
    val config = failuresOrConfig.right.get
    config.twilio shouldBe expectedTwilioConfig
    config.kafka shouldBe expectedKafkaConfig
    config.mailgun shouldBe expectedMailgunConfig
    config.email shouldBe expectedEmailConfig
    config.sms shouldBe expectedSmsConfig
    config.stannp shouldBe expectedStannpConfig
    config.aws shouldBe expectedAwsConfig
  }

  it should "bring the correct configuration implicits into scope" in {
    implicit val config = failuresOrConfig.right.get
    implicitly[TwilioAppConfig] shouldBe expectedTwilioConfig
    implicitly[KafkaAppConfig] shouldBe expectedKafkaConfig
    implicitly[MailgunAppConfig] shouldBe expectedMailgunConfig
    implicitly[EmailAppConfig] shouldBe expectedEmailConfig
    implicitly[SmsAppConfig] shouldBe expectedSmsConfig
    implicitly[StannpConfig] shouldBe expectedStannpConfig
    implicitly[AwsConfig] shouldBe expectedAwsConfig
  }
}
