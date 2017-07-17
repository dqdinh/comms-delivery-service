package com.ovoenergy.delivery.service.service.helpers

import cakesolutions.kafka.KafkaConsumer.{Conf => KafkaConsumerConf}
import cakesolutions.kafka.KafkaProducer.{Conf => KafkaProducerConf}
import cakesolutions.kafka.{KafkaConsumer, KafkaProducer}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.serialisation.Serialisation._
import com.ovoenergy.kafka.serialization.avro.{Authentication, SchemaRegistryClientSettings}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.Assertions
import org.apache.kafka.clients.consumer.{KafkaConsumer => ApacheKafkaConsumer}
import org.scalatest.concurrent.Eventually

import scala.annotation.tailrec
import scala.util.Random
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.reflect.ClassTag

//implicits
import com.ovoenergy.comms.serialisation.Codecs._
import com.sksamuel.avro4s._
import org.scalacheck.Shapeless._

trait KafkaTesting extends Assertions with Eventually {
  val aivenKafkaHosts     = "localhost:29093"
  val aivenZookeeperHosts = "localhost:32182"
  implicit val schemaRegistrySettings =
    SchemaRegistryClientSettings("http://localhost:8081", Authentication.None, 100, 1)

  val failedTopic            = "comms.failed.v2"
  val composedEmailTopic     = "comms.composed.email.v2"
  val composedSMSTopic       = "comms.composed.sms.v2"
  val issuedForDeliveryTopic = "comms.issued.for.delivery.v2"

  val consumerGroup = Random.nextString(10)

  lazy val composedEmailProducer = producer[ComposedEmailV2](composedEmailTopic)
  lazy val composedSMSProducer   = producer[ComposedSMSV2](composedSMSTopic)

  lazy val commFailedConsumer        = consumer[FailedV2](failedTopic)
  lazy val issuedForDeliveryConsumer = consumer[IssuedForDeliveryV2](issuedForDeliveryTopic)

  def producer[T: SchemaFor: ToRecord](topic: String) = {
    val serializer = avroBinarySchemaRegistrySerializer[T](schemaRegistrySettings, topic)
    KafkaProducer(
      KafkaProducerConf(new StringSerializer, serializer, aivenKafkaHosts)
    )
  }

  def consumer[T: SchemaFor: FromRecord: ClassTag](topic: String) = {
    import scala.collection.JavaConversions._
    val deserializer = avroBinarySchemaRegistryDeserializer[T](schemaRegistrySettings, topic)
    val consumer = KafkaConsumer(
      KafkaConsumerConf(new StringDeserializer, deserializer, aivenKafkaHosts, consumerGroup)
    )

    consumer.assign(Seq(new TopicPartition(topic, 0)))
    consumer
  }

  def pollForEvents[E](pollTime: FiniteDuration = 30.second,
                       noOfEventsExpected: Int,
                       consumer: ApacheKafkaConsumer[String, Option[E]],
                       topic: String): Seq[E] = {
    @tailrec
    def poll(deadline: Deadline, events: Seq[E]): Seq[E] = {
      if (deadline.hasTimeLeft) {
        val polledEvents: Seq[E] = consumer
          .poll(250)
          .records(topic)
          .asScala
          .toList
          .flatMap(_.value())
        val eventsSoFar: Seq[E] = events ++ polledEvents
        eventsSoFar.length match {
          case n if n == noOfEventsExpected => eventsSoFar
          case exceeded if exceeded > noOfEventsExpected =>
            throw new Exception(s"Consumed more than $noOfEventsExpected events from $topic")
          case _ => poll(deadline, eventsSoFar)
        }
      } else
        throw new Exception("Events didn't appear within the timelimit")
    }
    poll(pollTime.fromNow, Nil)
  }
}
