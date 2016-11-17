package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.Failed
import com.ovoenergy.delivery.service.AvroSerializer
import com.ovoenergy.delivery.service.email.mailgun.EmailProgressed
import org.apache.kafka.common.serialization.StringSerializer
import com.typesafe.config.Config

class KafkaProducers(config: Config) {
  val emailProgressedProducer   = KafkaProducer(Conf(new StringSerializer, new AvroSerializer[EmailProgressed], config.getString("kafka.bootstrap.servers")))
  val failedEmailProducer       = KafkaProducer(Conf(new StringSerializer, new AvroSerializer[Failed], config.getString("kafka.bootstrap.servers")))
}