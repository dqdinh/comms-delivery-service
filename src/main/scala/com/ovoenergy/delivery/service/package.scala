package com.ovoenergy.delivery

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

import scala.concurrent.duration.FiniteDuration

package object config {

  case class ApplicationConfig(mailgun: MailgunAppConfig,
                               email: EmailAppConfig,
                               sms: SmsAppConfig,
                               twilio: TwilioAppConfig,
                               kafka: KafkaAppConfig)
  case class EmailAppConfig(whitelist: String, blacklist: List[String])
  case class SmsAppConfig(whitelist: List[String], blacklist: List[String])
  case class KafkaAppConfig(retry: ExponentialDelayRetry)
  case class TwilioAppConfig(accountSid: String,
                             authToken: String,
                             serviceSid: String,
                             apiUrl: String,
                             retry: ConstantDelayRetry)
  case class MailgunAppConfig(host: String, apiKey: String, domain: String, retry: ConstantDelayRetry)
  case class ConstantDelayRetry(attempts: Int Refined Positive, interval: FiniteDuration)
  case class ExponentialDelayRetry(attempts: Int Refined Positive, initialInterval: FiniteDuration, exponent: Double)

  implicit def configuration2mailgun(implicit configuration: ApplicationConfig): MailgunAppConfig =
    configuration.mailgun
  implicit def configuration2Twilio(implicit configuration: ApplicationConfig): TwilioAppConfig = configuration.twilio
  implicit def configuration2Kafka(implicit configuration: ApplicationConfig): KafkaAppConfig   = configuration.kafka
  implicit def configuration2Email(implicit configuration: ApplicationConfig): EmailAppConfig   = configuration.email
  implicit def configuration2Sms(implicit configuration: ApplicationConfig): SmsAppConfig       = configuration.sms
}
