package com.ovoenergy.delivery.service

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.ovoenergy.comms.ComposedEmail
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.kafka.{DeliveryServiceEmailFlow, EmailDeliveryProcess, KafkaProducers}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.typesafe.config.ConfigFactory

object Main extends App with MailgunClient
with LoggingWithMDC {

  val loggerName = "Main"

  log.info("Delivery Service started")
  val config = ConfigFactory.load()

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

  val emailDeliveryProcesses = new EmailDeliveryProcess(producers.publishDeliveryFailedEvent, producers.publishDeliveryProgressedEvent, sendMail)

  DeliveryServiceEmailFlow[ComposedEmail](new AvroDeserializer[ComposedEmail], emailDeliveryProcesses.apply, kafkaConfig)

}
