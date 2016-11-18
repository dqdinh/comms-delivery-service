package com.ovoenergy.delivery.service.kafka

import akka.Done
import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.Failed
import com.ovoenergy.delivery.service.AvroSerializer
import com.ovoenergy.delivery.service.email.mailgun.EmailProgressed
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import scala.concurrent.ExecutionContext.Implicits.global

import scala.util.Try

class KafkaProducers(kafkaConfig: KafkaConfig) {

  private val deliveryProgressedProducer  = KafkaProducer(Conf(new StringSerializer, new AvroSerializer[EmailProgressed], kafkaConfig.hosts))
  private val deiveryFailedProducer       = KafkaProducer(Conf(new StringSerializer, new AvroSerializer[Failed], kafkaConfig.hosts))

  val publishDeliveryFailedEvent= (failed: Failed) => {
    deiveryFailedProducer.send(new ProducerRecord[String, Try[Failed]](kafkaConfig.commFailedTopic, Try(failed))).map(_ => Done)
  }

  val publishDeliveryProgressedEvent = (progressed: EmailProgressed) => {
    deliveryProgressedProducer.send(new ProducerRecord[String, Try[EmailProgressed]](kafkaConfig.emailProgressedTopic, Try(progressed))).map(_ => Done)
  }
}