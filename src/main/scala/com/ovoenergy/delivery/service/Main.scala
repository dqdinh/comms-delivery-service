package com.ovoenergy.delivery.service

import java.time.{Clock, Duration}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.ovoenergy.comms.model.ComposedEmail
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.delivery.service.email.BlackWhiteList
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import com.ovoenergy.delivery.service.kafka.{DeliveryFailedEventPublisher, DeliveryProgressedEventPublisher, DeliveryServiceGraph}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import com.ovoenergy.delivery.service.util.{Retry, UUIDGenerator}
import com.typesafe.config.ConfigFactory
import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.matching.Regex

object Main extends App

with LoggingWithMDC {

  val loggerName = "Main"

  implicit val clock = Clock.systemDefaultZone()

  private implicit class RichDuration(val duration: Duration) extends AnyVal {
    def toFiniteDuration: FiniteDuration = FiniteDuration.apply(duration.toNanos, TimeUnit.NANOSECONDS)
  }

  val config = ConfigFactory.load()

  val mailgunClientConfig = {
    val retryConfig = {
      val attempts = config.getInt("mailgun.attempts")
      RetryConfig(
        attempts = refineV[Positive](attempts).right.getOrElse(sys.error(s"mailgun.attempts must be positive but was $attempts")),
        backoff = Retry.Backoff.constantDelay(config.getDuration("mailgun.interval").toFiniteDuration)
      )
    }
    MailgunClient.Configuration(
      config.getString("mailgun.host"),
      config.getString("mailgun.domain"),
      config.getString("mailgun.apiKey"),
      HttpClient.apply,
      retryConfig
    )
  }

  val kafkaConfig = KafkaConfig(
    config.getString("kafka.hosts"),
    config.getString("kafka.group.id"),
    config.getString("kafka.topics.composed.email"),
    config.getString("kafka.topics.progressed.email"),
    config.getString("kafka.topics.failed")
  )

  val emailWhitelist: Regex = config.getString("email.whitelist").r
  val blackListedEmailAddresses = config.getStringList("email.blacklist")

  implicit val actorSystem = ActorSystem("kafka")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher

  val graph = DeliveryServiceGraph[ComposedEmail](
    avroDeserializer[ComposedEmail],
    EmailDeliveryProcess(
      BlackWhiteList.build(emailWhitelist, blackListedEmailAddresses),
      DeliveryFailedEventPublisher(kafkaConfig),
      DeliveryProgressedEventPublisher(kafkaConfig),
      UUIDGenerator.apply,
      clock,
      MailgunClient(mailgunClientConfig)),
    kafkaConfig)

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
