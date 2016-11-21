package com.ovoenergy.delivery.service

import java.time.Clock

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.ovoenergy.comms.ComposedEmail
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.{DeliveryServiceEmailFlow, KafkaProducers}
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.Serialization.composedEmailDeserializer
import com.ovoenergy.delivery.service.util.UUIDGenerator
import com.typesafe.config.ConfigFactory

object Main extends App

with LoggingWithMDC {

  val loggerName = "Main"

  implicit val clock = Clock.systemDefaultZone()

  val config = ConfigFactory.load()

  val mailgunClientConfig = MailgunClient.Configuration(config.getString("mailgun.domain"), config.getString("mailgun.apiKey"))
  val mailgunClient = new MailgunClient(mailgunClientConfig, HttpClient.processRequest, UUIDGenerator.generateUUID)

  log.info("Delivery Service started")

  val kafkaConfig = KafkaConfig(
    config.getString("kafka.hosts"),
    config.getString("kafka.group.id"),
    config.getString("kafka.topics.composed.email"),
    config.getString("kafka.topics.progressed.email"),
    config.getString("kafka.topics.failed")
  )

  implicit val actorSystem = ActorSystem("kafka")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher

  val producers = new KafkaProducers(kafkaConfig)

  val emailDeliveryProcesses = new EmailDeliveryProcess(producers.publishDeliveryFailedEvent, producers.publishDeliveryProgressedEvent, mailgunClient.sendEmail)

  DeliveryServiceEmailFlow[ComposedEmail](composedEmailDeserializer, emailDeliveryProcesses.apply, kafkaConfig)

}
