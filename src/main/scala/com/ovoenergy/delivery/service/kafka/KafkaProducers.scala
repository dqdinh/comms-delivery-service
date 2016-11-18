package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.EmailProgressed
import com.ovoenergy.delivery.service.Serialization.{deliveryErrorSerializer, emailProgressedSerializer}
import com.ovoenergy.delivery.service.email.mailgun.EmailDeliveryError
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.ExecutionContext.Implicits.global

class KafkaProducers(kafkaConfig: KafkaConfig) {

  private val deliveryProgressedProducer  = KafkaProducer(Conf(new StringSerializer, emailProgressedSerializer, kafkaConfig.hosts))
  private val deiveryFailedProducer       = KafkaProducer(Conf(new StringSerializer, deliveryErrorSerializer, kafkaConfig.hosts))

  val publishDeliveryFailedEvent= (delivertyErr: EmailDeliveryError) => {
    deiveryFailedProducer.send(new ProducerRecord[String, EmailDeliveryError](kafkaConfig.commFailedTopic, delivertyErr)).map(_ => ())
  }

  val publishDeliveryProgressedEvent = (progressed: EmailProgressed) => {
    deliveryProgressedProducer.send(new ProducerRecord[String, EmailProgressed](kafkaConfig.emailProgressedTopic, progressed)).map(_ => ())
  }
}