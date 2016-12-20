package com.ovoenergy.delivery.service

import java.time.Clock

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.ovoenergy.comms.model.ComposedEmail
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.delivery.service.email.BlackListed
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import com.ovoenergy.delivery.service.kafka.{DeliveryFailedEventPublisher, DeliveryProgressedEventPublisher, DeliveryServiceGraph}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.UUIDGenerator
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConversions.asScalaBuffer
import scala.io.Source

object Main extends App

with LoggingWithMDC {

  val loggerName = "Main"

  implicit val clock = Clock.systemDefaultZone()

  val config = ConfigFactory.load()

  val mailgunClientConfig = MailgunClient.Configuration(
    config.getString("mailgun.host"),
    config.getString("mailgun.domain"),
    config.getString("mailgun.apiKey"),
    HttpClient.apply
  )

  log.info("Delivery Service started")

  val kafkaConfig = KafkaConfig(
    config.getString("kafka.hosts"),
    config.getString("kafka.group.id"),
    config.getString("kafka.topics.composed.email"),
    config.getString("kafka.topics.progressed.email"),
    config.getString("kafka.topics.failed")
  )

  val blackListedEmailAddresses = config.getStringList("email.blacklist")

  implicit val actorSystem = ActorSystem("kafka")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher

  val graph = DeliveryServiceGraph[ComposedEmail](
    avroDeserializer[ComposedEmail],
    EmailDeliveryProcess(
      BlackListed(blackListedEmailAddresses),
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