package com.ovoenergy.delivery.service.service.helpers

import cakesolutions.kafka.KafkaConsumer.{Conf => KafkaConsumerConf}
import cakesolutions.kafka.KafkaProducer.{Conf => KafkaProducerConf}
import cakesolutions.kafka.{KafkaConsumer, KafkaProducer}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email._
import com.ovoenergy.comms.model.sms._
import com.ovoenergy.comms.serialisation.Serialisation.{avroDeserializer, avroSerializer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.Assertions
import org.apache.kafka.clients.consumer.{KafkaConsumer => ApacheKafkaConsumer}

import scala.annotation.tailrec
import scala.util.Random
import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import scala.concurrent.duration._

//implicits
import com.ovoenergy.comms.serialisation.Decoders._
import io.circe.generic.auto._
import org.scalacheck.Shapeless._

trait KafkaTesting extends Assertions {

  val kafkaHosts     = "localhost:29092"
  val zookeeperHosts = "localhost:32181"

  val consumerGroup = Random.nextString(10)
  val composedEmailProducer = KafkaProducer(
    KafkaProducerConf(new StringSerializer, avroSerializer[ComposedEmail], kafkaHosts))
  val composedSMSProducer = KafkaProducer(
    KafkaProducerConf(new StringSerializer, avroSerializer[ComposedSMS], kafkaHosts))
  val commFailedConsumer = KafkaConsumer(
    KafkaConsumerConf(new StringDeserializer, avroDeserializer[Failed], kafkaHosts, consumerGroup))
  val emailProgressedConsumer = KafkaConsumer(
    KafkaConsumerConf(new StringDeserializer, avroDeserializer[EmailProgressed], kafkaHosts, consumerGroup))
  val smsProgressedConsumer = KafkaConsumer(
    KafkaConsumerConf(new StringDeserializer, avroDeserializer[SMSProgressed], kafkaHosts, consumerGroup))
  val issuedForDeliveryConsumer = KafkaConsumer(
    KafkaConsumerConf(new StringDeserializer, avroDeserializer[IssuedForDelivery], kafkaHosts, consumerGroup))

  val failedTopic            = "comms.failed"
  val composedEmailTopic     = "comms.composed.email"
  val composedSMSTopic       = "comms.composed.sms"
  val issuedForDeliveryTopic = "comms.issued.for.delivery"

  def createTopicsAndSubscribe() {

    import _root_.kafka.admin.AdminUtils
    import _root_.kafka.utils.ZkUtils

    import scala.concurrent.duration._

    val zkUtils = ZkUtils(zookeeperHosts, 30000, 5000, isZkSecurityEnabled = false)

    def createTopic[E](topic: String, consumer: ApacheKafkaConsumer[String, Option[E]]) = {
      if (!AdminUtils.topicExists(zkUtils, topic)) {
        AdminUtils.createTopic(zkUtils, topic, 1, 1)
      }
      consumer.assign(Seq(new TopicPartition(topic, 0)).asJava)
      consumer.poll(200).records(topic)

      if (!AdminUtils.topicExists(zkUtils, topic))
        throw new Exception(s"Failed to create $topic")
    }

    //Wait until kafka calls are not erroring and the service has created the composedEmailTopic
    val timeout    = 15.seconds.fromNow
    var notStarted = true
    while (timeout.hasTimeLeft && notStarted) {
      try {
        notStarted = {
          !AdminUtils.topicExists(zkUtils, composedEmailTopic) && !AdminUtils.topicExists(zkUtils, composedSMSTopic)
        }
        createTopic(failedTopic, commFailedConsumer)
        createTopic(issuedForDeliveryTopic, issuedForDeliveryConsumer)
      } catch {
        case NonFatal(ex) => Thread.sleep(100)
      }
    }

    if (notStarted) fail("Services did not start within 10 seconds")
  }

  def pollForEvents[E](pollTime: FiniteDuration = 10000.millisecond,
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
