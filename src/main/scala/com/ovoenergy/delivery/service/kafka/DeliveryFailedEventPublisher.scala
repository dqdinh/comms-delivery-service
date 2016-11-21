package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.delivery.service.Serialization._
import com.ovoenergy.delivery.service.email.mailgun.EmailDeliveryError
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer

object DeliveryFailedEventPublisher {

  def apply(kafkaConfig: KafkaConfig)(delivertyErr: EmailDeliveryError) = {
    val deiveryFailedProducer = KafkaProducer(Conf(new StringSerializer, deliveryErrorSerializer, kafkaConfig.hosts))
    deiveryFailedProducer.send(new ProducerRecord[String, EmailDeliveryError](kafkaConfig.commFailedTopic, delivertyErr))
  }

}
