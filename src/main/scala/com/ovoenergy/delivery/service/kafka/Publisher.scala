package com.ovoenergy.delivery.service.kafka

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.akka.streams.Factory.SSLConfig
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.kafka.serialization.avro.SchemaRegistryClientSettings
import com.sksamuel.avro4s.{SchemaFor, ToRecord}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.Future

object Publisher {
  import com.ovoenergy.kafka.serialization.avro4s._

  def producerFor[T: SchemaFor: ToRecord](kafkaConfig: KafkaConfig,
                                          sslConfigMaybe: Option[SSLConfig],
                                          schemaRegistrySettings: SchemaRegistryClientSettings) = {
    val initialSettings = Conf(new StringSerializer,
                               avroBinarySchemaIdSerializer[T](schemaRegistrySettings, isKey = false),
                               kafkaConfig.hosts)

    val producerSettings = sslConfigMaybe
      .map(
        ssl =>
          initialSettings
            .withProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
            .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ssl.keystoreLocation.toAbsolutePath.toString)
            .withProperty(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, ssl.keystoreType.toString)
            .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, ssl.keystorePassword)
            .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, ssl.keyPassword)
            .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ssl.truststoreLocation.toString)
            .withProperty(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, ssl.truststoreType.toString)
            .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, ssl.truststorePassword))
      .getOrElse(initialSettings)

    KafkaProducer(producerSettings)
  }

  def publishEvent[T: SchemaFor: ToRecord](topic: String)(event: T)(
      implicit producer: KafkaProducer[String, T]): Future[RecordMetadata] = {
    producer.send(new ProducerRecord[String, T](topic, event))
  }
}
