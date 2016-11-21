package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.EmailProgressed
import com.ovoenergy.delivery.service.Serialization.emailProgressedSerializer
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer

object DeliveryProgressedEventPublisher {

  def apply(kafkaConfig: KafkaConfig)(progressed: EmailProgressed) = {
    val deliveryProgressedProducer  = KafkaProducer(Conf(new StringSerializer, emailProgressedSerializer, kafkaConfig.hosts))
    deliveryProgressedProducer.send(new ProducerRecord[String, EmailProgressed](kafkaConfig.emailProgressedTopic, progressed))
  }
}