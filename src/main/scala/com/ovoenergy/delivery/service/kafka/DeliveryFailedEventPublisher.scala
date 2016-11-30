package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.model.Failed
import com.ovoenergy.delivery.service.Serialization._
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer

object DeliveryFailedEventPublisher {

  def apply(kafkaConfig: KafkaConfig)(failed: Failed) = {
    val deliveryFailedProducer = KafkaProducer(Conf(new StringSerializer, failedSerializer, kafkaConfig.hosts))
    deliveryFailedProducer.send(new ProducerRecord[String, Failed](kafkaConfig.commFailedTopic, failed))
  }

}
