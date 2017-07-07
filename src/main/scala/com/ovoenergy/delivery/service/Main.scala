package com.ovoenergy.delivery.service

import java.nio.file.Paths
import java.time.{Clock, Duration}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.RunnableGraph
import cakesolutions.kafka.KafkaProducer
import com.ovoenergy.comms.akka.streams.Factory
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.comms.model.sms.ComposedSMSV2
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.delivery.service.email.IssueEmail
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.{DeliveryServiceGraph, Publisher}
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.kafka.process.{FailedEvent, IssuedForDeliveryEvent}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.sms.IssueSMS
import com.ovoenergy.delivery.service.sms.twilio.TwilioClient
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import com.ovoenergy.delivery.service.validation.{BlackWhiteList, ExpiryCheck}
import com.ovoenergy.kafka.serialization.avro.SchemaRegistryClientSettings
import com.typesafe.config.ConfigFactory
import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import org.apache.kafka.common.serialization.StringSerializer

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
  val emailWhitelist: Regex  = config.getString("email.whitelist").r

  val blackListedEmailAddresses = config.getStringList("email.blacklist").asScala

  val smsWhiteList = config.getStringList("sms.whiteList").asScala
  val smsBlacklist = config.getStringList("sms.blackList").asScala

  val aivenSchemaRegistryEndpoint = config.getString("kafka.schema.registry.url")
  val aivenSchemaRegistryUsername = config.getString("kafka.schema.registry.username")
  val aivenSchemaRegistryPassword = config.getString("kafka.schema.registry.password")
  val schemaRegistrySettings =
    SchemaRegistryClientSettings(aivenSchemaRegistryEndpoint, aivenSchemaRegistryUsername, aivenSchemaRegistryPassword)

  implicit val actorSystem      = ActorSystem("kafka")
  implicit val materializer     = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  implicit val legacyKafkaConfig = KafkaConfig(
    config.getString("kafka.legacy.hosts"),
    config.getString("kafka.group-id")
  )

  implicit val aivenKafkaConfig = KafkaConfig(
    config.getString("kafka.aiven.hosts"),
    config.getString("kafka.group-id")
  )

  val kafkaSSLConfig = {
    if (config.getBoolean("kafka.ssl.enabled")) {
      Some(
        Factory.SSLConfig(
          keystoreLocation = Paths.get(config.getString("kafka.ssl.keystore.location")),
          keystoreType = Factory.StoreType.PKCS12,
          keystorePassword = config.getString("kafka.ssl.keystore.password"),
          keyPassword = config.getString("kafka.ssl.key.password"),
          truststoreLocation = Paths.get(config.getString("kafka.ssl.truststore.location")),
          truststoreType = Factory.StoreType.JKS,
          truststorePassword = config.getString("kafka.ssl.truststore.password")
        ))
    } else None
  }

  implicit val failedProducer =
    Publisher.producerFor[FailedV2](aivenKafkaConfig, kafkaSSLConfig, schemaRegistrySettings)
  implicit val issuedForDeliveryProducer =
    Publisher.producerFor[IssuedForDeliveryV2](aivenKafkaConfig, kafkaSSLConfig, schemaRegistrySettings)
  implicit val scheduler = actorSystem.scheduler

  val failedPublisher            = Publisher.publishEvent[FailedV2](failedTopic) _
  val issuedForDeliveryPublisher = Publisher.publishEvent[IssuedForDeliveryV2](issuedForDeliveryTopic) _

  val emailGraphLegacy = DeliveryServiceGraph[ComposedEmailV2](
    consumerDeserializer = avroDeserializer[ComposedEmailV2],
    issueComm = IssueEmail.issue(
      checkBlackWhiteList = BlackWhiteList.buildFromRegex(emailWhitelist, blackListedEmailAddresses),
      isExpired = ExpiryCheck.isExpired(clock),
      sendEmail = MailgunClient.sendEmail(mailgunClientConfig)
    ),
    kafkaConfig = legacyKafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    sslConfig = None,
    consumerTopic = composedEmailTopic,
    sendFailedEvent = FailedEvent.email(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.email(issuedForDeliveryPublisher)
  )

  val emailGraphAiven = DeliveryServiceGraph[ComposedEmailV2](
    consumerDeserializer =
      avroBinarySchemaRegistryDeserializer[ComposedEmailV2](schemaRegistrySettings, composedEmailTopic),
    issueComm = IssueEmail.issue(
      checkBlackWhiteList = BlackWhiteList.buildFromRegex(emailWhitelist, blackListedEmailAddresses),
      isExpired = ExpiryCheck.isExpired(clock),
      sendEmail = MailgunClient.sendEmail(mailgunClientConfig)
    ),
    kafkaConfig = aivenKafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    sslConfig = kafkaSSLConfig,
    consumerTopic = composedEmailTopic,
    sendFailedEvent = FailedEvent.email(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.email(issuedForDeliveryPublisher)
  )

  val smsGraphLegacy: RunnableGraph[Control] = DeliveryServiceGraph[ComposedSMSV2](
    consumerDeserializer = avroDeserializer[ComposedSMSV2],
    issueComm = IssueSMS.issue(
      checkBlackWhiteList = BlackWhiteList.buildFromLists(smsWhiteList, smsBlacklist),
      isExpired = ExpiryCheck.isExpired(clock),
      sendSMS = TwilioClient.send(twilioClientConfig)
    ),
    kafkaConfig = legacyKafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    sslConfig = None,
    consumerTopic = composedSMSTopic,
    sendFailedEvent = FailedEvent.sms(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.sms(issuedForDeliveryPublisher)
  )

  val smsGraphAiven: RunnableGraph[Control] = DeliveryServiceGraph[ComposedSMSV2](
    consumerDeserializer =
      avroBinarySchemaRegistryDeserializer[ComposedSMSV2](schemaRegistrySettings, composedSMSTopic),
    issueComm = IssueSMS.issue(
      checkBlackWhiteList = BlackWhiteList.buildFromLists(smsWhiteList, smsBlacklist),
      isExpired = ExpiryCheck.isExpired(clock),
      sendSMS = TwilioClient.send(twilioClientConfig)
    ),
    kafkaConfig = aivenKafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    sslConfig = kafkaSSLConfig,
    consumerTopic = composedSMSTopic,
    sendFailedEvent = FailedEvent.sms(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.sms(issuedForDeliveryPublisher)
  )

  for (line <- Source.fromFile("./banner.txt").getLines) {
    println(line)
  }

  log.info("Delivery Service started")
  setupGraph(emailGraphAiven, "Email", "Aiven")
  setupGraph(smsGraphAiven, "SMS", "Aiven")

  setupGraph(emailGraphLegacy, "Email", "Legacy")
  setupGraph(smsGraphLegacy, "SMS", "Legacy")

  private def setupGraph(graph: RunnableGraph[Control], graphName: String, kafkaHostName: String) = {
    val control: Control = graph.run()
    control.isShutdown.foreach { _ =>
      log.error(
        s"ARGH! The Kafka source has shut down for graph $graphName in host $kafkaHostName. Killing the JVM and nuking from orbit.")
      System.exit(1)
    }
  }
}
