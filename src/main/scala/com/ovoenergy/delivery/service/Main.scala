package com.ovoenergy.delivery.service

import java.time.Clock

import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
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

  while (true) {
    Thread.sleep(1000)
  }


//  val yo = DeliveryServiceFlow("123", "123", new AvroDeserializer[ComposedEmail] ???)
}
