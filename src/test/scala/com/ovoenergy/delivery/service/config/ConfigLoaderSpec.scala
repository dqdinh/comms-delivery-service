package com.ovoenergy.delivery.service.config

import com.ovoenergy.delivery.config.{
  ConstantDelayRetry,
  EmailAppConfig,
  ExponentialDelayRetry,
  KafkaAppConfig,
  MailgunAppConfig,
  SmsAppConfig,
  TwilioAppConfig
}
import com.ovoenergy.delivery.service.ConfigLoader
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

  it should "load the configuration file" in {

    failuresOrConfig shouldBe 'right
    val config = failuresOrConfig.right.get
    config.twilio shouldBe expectedTwilioConfig
    config.kafka shouldBe expectedKafkaConfig
    config.mailgun shouldBe expectedMailgunConfig
    config.email shouldBe expectedEmailConfig
    config.sms shouldBe expectedSmsConfig
  }

  it should "bring the correct configuration implicits into scope" in {
    implicit val config = failuresOrConfig.right.get
    implicitly[TwilioAppConfig] shouldBe expectedTwilioConfig
    implicitly[KafkaAppConfig] shouldBe expectedKafkaConfig
    implicitly[MailgunAppConfig] shouldBe expectedMailgunConfig
    implicitly[EmailAppConfig] shouldBe expectedEmailConfig
    implicitly[SmsAppConfig] shouldBe expectedSmsConfig
  }

}
