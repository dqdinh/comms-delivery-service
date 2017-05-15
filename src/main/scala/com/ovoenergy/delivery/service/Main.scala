package com.ovoenergy.delivery.service

import java.time.{Clock, Duration}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.RunnableGraph
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.{ComposedEmail, ComposedEmailV2}
import com.ovoenergy.comms.model.sms.{ComposedSMS, ComposedSMSV2}
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.delivery.service.email.IssueEmail
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.{
  DeliveryServiceGraph,
  DeliveryServiceGraphLegacy,
  LegacyEventConversion,
  Publisher
}
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.kafka.process.{FailedEvent, IssuedForDeliveryEvent}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.sms.IssueSMS
import com.ovoenergy.delivery.service.sms.twilio.TwilioClient
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import com.ovoenergy.delivery.service.validation.{BlackWhiteList, ExpiryCheck}
import com.typesafe.config.ConfigFactory
import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import io.circe.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.matching.Regex

object Main extends App with LoggingWithMDC {

  implicit val clock = Clock.systemDefaultZone()

  private implicit class RichDuration(val duration: Duration) extends AnyVal {
    def toFiniteDuration: FiniteDuration = FiniteDuration.apply(duration.toNanos, TimeUnit.NANOSECONDS)
  }

  val config           = ConfigFactory.load()
  val twilioServiceSid = config.getString("twilio.serviceSid")

  val mailgunClientConfig = {
    val mailgunRetryConfig = {
      val attempts = config.getInt("mailgun.attempts")
      RetryConfig(
        attempts = refineV[Positive](attempts).right
          .getOrElse(sys.error(s"mailgun.attempts must be positive but was $attempts")),
        backoff = Retry.Backoff.constantDelay(config.getDuration("mailgun.interval").toFiniteDuration)
      )
    }
    MailgunClient.Configuration(
      config.getString("mailgun.host"),
      config.getString("mailgun.domain"),
      config.getString("mailgun.apiKey"),
      HttpClient.apply,
      mailgunRetryConfig
    )
  }

  val kafkaProducerRetryConfig = {
    val attempts = config.getInt("kafka.producer.retry.attempts")
    Retry.RetryConfig(
      attempts = refineV[Positive](attempts).right
        .getOrElse(sys.error(s"Kafka retry attempts must be positive but was $attempts")),
      backoff = Retry.Backoff.exponential(
        config.getDuration("kafka.producer.retry.initialInterval").toFiniteDuration,
        config.getDouble("kafka.producer.retry.exponent")
      )
    )
  }

  val twilioClientConfig = {
    val retryConfig = {
      val attempts = config.getInt("twilio.attempts")
      RetryConfig(
        attempts = refineV[Positive](attempts).right
          .getOrElse(sys.error(s"twilio attempts must be positive but was $attempts")),
        backoff = Retry.Backoff.constantDelay(config.getDuration("twilio.interval").toFiniteDuration)
      )
    }
    TwilioClient.Config(
      config.getString("twilio.accountSid"),
      config.getString("twilio.authToken"),
      config.getString("twilio.serviceSid"),
      config.getString("twilio.api.url"),
      retryConfig,
      HttpClient.apply
    )
  }

  val composedEmailTopic     = config.getString("kafka.topics.composed.email.v2")
  val composedSMSTopic       = config.getString("kafka.topics.composed.sms.v2")
  val failedTopic            = config.getString("kafka.topics.failed.v2")
  val issuedForDeliveryTopic = config.getString("kafka.topics.issued.for.delivery.v2")

  val composedEmailLegacyTopic     = config.getString("kafka.topics.composed.email.v1")
  val composedSMSLegacyTopic       = config.getString("kafka.topics.composed.sms.v1")
  val failedLegacyTopic            = config.getString("kafka.topics.failed.v1")
  val issuedForDeliveryLegacyTopic = config.getString("kafka.topics.issued.for.delivery.v1")

  val emailWhitelist: Regex     = config.getString("email.whitelist").r
  val blackListedEmailAddresses = config.getStringList("email.blacklist").asScala

  val smsWhiteList = config.getStringList("sms.whiteList").asScala
  val smsBlacklist = config.getStringList("sms.blackList").asScala

  implicit val actorSystem      = ActorSystem("kafka")
  implicit val materializer     = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  implicit val kafkaConfig = KafkaConfig(
    config.getString("kafka.hosts"),
    config.getString("kafka.group.id")
  )
  implicit val scheduler = actorSystem.scheduler

  val failedPublisher            = Publisher.publishEvent[FailedV2](failedTopic) _
  val issuedForDeliveryPublisher = Publisher.publishEvent[IssuedForDeliveryV2](issuedForDeliveryTopic) _

  val emailGraph = DeliveryServiceGraph[ComposedEmailV2](
    consumerDeserializer = avroDeserializer[ComposedEmailV2],
    issueComm = IssueEmail.issue(
      checkBlackWhiteList = BlackWhiteList.buildFromRegex(emailWhitelist, blackListedEmailAddresses),
      isExpired = ExpiryCheck.isExpired(clock),
      sendEmail = MailgunClient.sendEmail(mailgunClientConfig)
    ),
    kafkaConfig = kafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    consumerTopic = composedEmailTopic,
    sendFailedEvent = FailedEvent.email(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.email(issuedForDeliveryPublisher)
  )

  val legacyEmailGraph = DeliveryServiceGraphLegacy[ComposedEmail, ComposedEmailV2](
    consumerDeserializer = avroDeserializer[ComposedEmail],
    convertEvent = LegacyEventConversion.toComposedEmailV2,
    issueComm = IssueEmail.issue(
      checkBlackWhiteList = BlackWhiteList.buildFromRegex(emailWhitelist, blackListedEmailAddresses),
      isExpired = ExpiryCheck.isExpired(clock),
      sendEmail = MailgunClient.sendEmail(mailgunClientConfig)
    ),
    kafkaConfig = kafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    consumerTopic = composedEmailLegacyTopic,
    sendFailedEvent = FailedEvent.email(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.email(issuedForDeliveryPublisher)
  )

  val smsGraph: RunnableGraph[Control] = DeliveryServiceGraph[ComposedSMSV2](
    consumerDeserializer = avroDeserializer[ComposedSMSV2],
    issueComm = IssueSMS.issue(
      checkBlackWhiteList = BlackWhiteList.buildFromLists(smsWhiteList, smsBlacklist),
      isExpired = ExpiryCheck.isExpired(clock),
      sendSMS = TwilioClient.send(twilioClientConfig)
    ),
    kafkaConfig = kafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    consumerTopic = composedSMSTopic,
    sendFailedEvent = FailedEvent.sms(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.sms(issuedForDeliveryPublisher)
  )

  val legacySmsGraph: RunnableGraph[Control] = DeliveryServiceGraphLegacy[ComposedSMS, ComposedSMSV2](
    consumerDeserializer = avroDeserializer[ComposedSMS],
    convertEvent = LegacyEventConversion.toComposedSMSV2,
    issueComm = IssueSMS.issue(
      checkBlackWhiteList = BlackWhiteList.buildFromLists(smsWhiteList, smsBlacklist),
      isExpired = ExpiryCheck.isExpired(clock),
      sendSMS = TwilioClient.send(twilioClientConfig)
    ),
    kafkaConfig = kafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    consumerTopic = composedSMSLegacyTopic,
    sendFailedEvent = FailedEvent.sms(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.sms(issuedForDeliveryPublisher)
  )

  for (line <- Source.fromFile("./banner.txt").getLines) {
    println(line)
  }

  log.info("Delivery Service started")
  setupGraph(emailGraph, "Email")
  setupGraph(smsGraph, "SMS")
  setupGraph(legacyEmailGraph, "Email (legacy)")
  setupGraph(legacySmsGraph, "SMS (legacy)")

  private def setupGraph(graph: RunnableGraph[Control], graphName: String) = {
    val control: Control = graph.run()
    control.isShutdown.foreach { _ =>
      log.error(s"ARGH! The Kafka source has shut down for graph $graphName. Killing the JVM and nuking from orbit.")
      System.exit(1)
    }
  }
}
