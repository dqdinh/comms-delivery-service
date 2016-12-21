package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.model.EmailProgressed
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import scala.concurrent.Future

object DeliveryProgressedEventPublisher {

  def apply(kafkaConfig: KafkaConfig): EmailProgressed => Future[RecordMetadata] = {
    val producer  = KafkaProducer(Conf(new StringSerializer, avroSerializer[EmailProgressed], kafkaConfig.hosts))

    (progressed: EmailProgressed) => {
      producer.send(new ProducerRecord[String, EmailProgressed](
        kafkaConfig.emailProgressedTopic, 
        progressed.metadata.customerId, 
        progressed
      ))
    }
  }
}
