package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.sksamuel.avro4s.{SchemaFor, ToRecord}
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.Future

object Publisher {

  def publishEvent[T: SchemaFor: ToRecord](topic: String)(event: T)(
      implicit kafkaConfig: KafkaConfig): Future[RecordMetadata] = {
    val producer = KafkaProducer(Conf(new StringSerializer, avroSerializer[T], kafkaConfig.hosts))
    producer.send(new ProducerRecord[String, T](topic, event))
  }
}
