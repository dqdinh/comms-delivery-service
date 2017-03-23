package com.ovoenergy.delivery.service

import java.time.{Clock, Duration}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.comms.serialisation.Decoders._
import com.ovoenergy.delivery.service.email.IssueEmail
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.kafka.process.{FailedEvent, IssuedForDeliveryEvent}
import com.ovoenergy.delivery.service.kafka.process.email.EmailProgressedEvent
import com.ovoenergy.delivery.service.kafka.{DeliveryServiceGraph, Publisher}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.validation.{BlackWhiteList, ExpiryCheck}
import com.typesafe.config.ConfigFactory
import io.circe.generic.auto._
import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.matching.Regex

object Main extends App with LoggingWithMDC {

  implicit val clock = Clock.systemDefaultZone()

  private implicit class RichDuration(val duration: Duration) extends AnyVal {
    def toFiniteDuration: FiniteDuration = FiniteDuration.apply(duration.toNanos, TimeUnit.NANOSECONDS)
  }

  val config = ConfigFactory.load()

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

  val composedEmailTopic     = config.getString("kafka.topics.composed.email")
  val progressedEmailTopic   = config.getString("kafka.topics.progressed.email")
  val failedTopic            = config.getString("kafka.topics.failed")
  val issuedForDeliveryTopic = config.getString("kafka.topics.issued.for.delivery")

  val emailWhitelist: Regex     = config.getString("email.whitelist").r
  val blackListedEmailAddresses = config.getStringList("email.blacklist")

  implicit val actorSystem      = ActorSystem("kafka")
  implicit val materializer     = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  implicit val kafkaConfig = KafkaConfig(
    config.getString("kafka.hosts"),
    config.getString("kafka.group.id")
  )
  implicit val scheduler = actorSystem.scheduler

  val failedPublisher            = Publisher.publishEvent[Failed](failedTopic) _
  val issuedForDeliveryPublisher = Publisher.publishEvent[IssuedForDelivery](issuedForDeliveryTopic) _

  val emailProgressedPublisher = Publisher.publishEvent[EmailProgressed](progressedEmailTopic) _

  val graph = DeliveryServiceGraph[ComposedEmail](
    consumerDeserializer = avroDeserializer[ComposedEmail],
    issueComm = IssueEmail.issue(
      checkBlackWhiteList = BlackWhiteList.build(emailWhitelist, blackListedEmailAddresses),
      isExpired = ExpiryCheck.isExpired(clock),
      sendEmail = MailgunClient.sendEmail(mailgunClientConfig)
    ),
    kafkaConfig = kafkaConfig,
    retryConfig = kafkaProducerRetryConfig,
    consumerTopic = composedEmailTopic,
    sendFailedEvent = FailedEvent.send(failedPublisher),
    sendCommProgressedEvent = EmailProgressedEvent.send(emailProgressedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.send(issuedForDeliveryPublisher)
  )

  for (line <- Source.fromFile("./banner.txt").getLines) {
    println(line)
  }

  val control = graph.run()

  log.info("Delivery Service started")

  control.isShutdown.foreach { _ =>
    log.error("ARGH! The Kafka source has shut down. Killing the JVM and nuking from orbit.")
    System.exit(1)
  }
}
