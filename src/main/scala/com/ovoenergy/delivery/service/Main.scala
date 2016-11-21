package com.ovoenergy.delivery.service

import java.time.Clock

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.ovoenergy.comms.ComposedEmail
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.{DeliveryFailedEventPublisher, DeliveryProgressedEventPublisher, DeliveryServiceFlow}
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.Serialization.composedEmailDeserializer
import com.ovoenergy.delivery.service.email.BlackListed
import com.ovoenergy.delivery.service.util.UUIDGenerator
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConversions.asScalaBuffer

object Main extends App

with LoggingWithMDC {

  val loggerName = "Main"

  implicit val clock = Clock.systemDefaultZone()

  val config = ConfigFactory.load()

  val mailgunClientConfig = MailgunClient.Configuration(
    config.getString("mailgun.domain"),
    config.getString("mailgun.apiKey"),
    HttpClient.apply,
    UUIDGenerator.apply
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

  DeliveryServiceFlow[ComposedEmail](
    composedEmailDeserializer,
    EmailDeliveryProcess(
      BlackListed(blackListedEmailAddresses),
      DeliveryFailedEventPublisher(kafkaConfig),
      DeliveryProgressedEventPublisher(kafkaConfig),
      MailgunClient(mailgunClientConfig)),
    kafkaConfig)

}
