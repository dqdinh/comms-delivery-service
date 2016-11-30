package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.model.EmailProgressed
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import org.apache.kafka.clients.producer.ProducerRecord
import com.ovoenergy.comms.serialisation.Serialisation._
import org.apache.kafka.common.serialization.StringSerializer

object DeliveryProgressedEventPublisher {

  def apply(kafkaConfig: KafkaConfig)(progressed: EmailProgressed) = {
    val deliveryProgressedProducer  = KafkaProducer(Conf(new StringSerializer, avroSerializer[EmailProgressed], kafkaConfig.hosts))
    deliveryProgressedProducer.send(new ProducerRecord[String, EmailProgressed](kafkaConfig.emailProgressedTopic, progressed))
  }
}