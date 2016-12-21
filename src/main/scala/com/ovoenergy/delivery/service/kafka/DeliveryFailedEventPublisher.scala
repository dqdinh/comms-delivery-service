package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.model.Failed
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import scala.concurrent.Future

object DeliveryFailedEventPublisher {

  def apply(kafkaConfig: KafkaConfig): Failed => Future[RecordMetadata] = {
    val producer = KafkaProducer(Conf(new StringSerializer, avroSerializer[Failed], kafkaConfig.hosts))

    (failed: Failed) => {
      producer.send(new ProducerRecord[String, Failed](
        kafkaConfig.commFailedTopic, 
        failed.metadata.customerId,
        failed
      ))
    }
  }

}
