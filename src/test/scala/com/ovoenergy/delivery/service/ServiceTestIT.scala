package com.ovoenergy.delivery.service

import cakesolutions.kafka.KafkaConsumer.{Conf => KafkaConsumerConf}
import cakesolutions.kafka.KafkaProducer.{Conf => KafkaProducerConf}
import cakesolutions.kafka.{KafkaConsumer, KafkaProducer}
import com.ovoenergy.comms.model.{ComposedEmail, EmailProgressed, Failed}
import com.ovoenergy.comms.model.EmailStatus.Queued
import com.ovoenergy.comms.serialisation.Serialisation._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.mockserver.client.server.MockServerClient
import org.mockserver.model.HttpRequest.request
import com.ovoenergy.comms.serialisation.Serialisation._
import org.mockserver.model.HttpResponse.response
import org.scalacheck.Shapeless._
import org.scalacheck.Arbitrary
import org.scalatest.{Failed => _, _}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}

import scala.collection.JavaConverters._
import scala.util.Random
import scala.util.control.NonFatal

class ServiceTestIT extends FlatSpec
  with Matchers
  with GeneratorDrivenPropertyChecks
  with ScalaFutures
  with OneInstancePerTest {

  object DockerComposeTag extends Tag("DockerComposeTag")

  implicit val config: PatienceConfig = PatienceConfig(Span(60, Seconds))

  val kafkaHosts = "localhost:29092"
  val zookeeperHosts = "localhost:32181"

  val consumerGroup = Random.nextString(10)
  val composedEmailProducer = KafkaProducer(KafkaProducerConf(new StringSerializer, avroSerializer[ComposedEmail], kafkaHosts))
  val commFailedConsumer = KafkaConsumer(KafkaConsumerConf(new StringDeserializer, avroDeserializer[Failed], kafkaHosts, consumerGroup))
  val emailProgressedConsumer = KafkaConsumer(KafkaConsumerConf(new StringDeserializer, avroDeserializer[EmailProgressed], kafkaHosts, consumerGroup))

  val failedTopic = "comms.failed"
  val composedEmailTopic = "comms.composed.email"
  val emailProgressedTopic = "comms.progressed.email"

  val mockServerClient = new MockServerClient("localhost", 1080)

  behavior of "producer"

    it should "create Failed event when authentication fails with Mailgun" taggedAs DockerComposeTag in {
      createTopicsAndSubscribe()
      create401MailgunResponse()

      val composedEmailEvent = Arbitrary.arbitrary[ComposedEmail].sample.get
      val future = composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, composedEmailEvent))
      whenReady(future) {
        case _ =>
          val failedEvents = commFailedConsumer.poll(30000).records(failedTopic).asScala.toList
          failedEvents.size shouldBe 1
          failedEvents.foreach(record => {
            val failed = record.value().getOrElse(fail("No record for ${record.key()}"))
            failed.reason shouldBe "Error authenticating with the Email Gateway"
          })
      }
    }

    it should "create Failed event when get bad request from Mailgun" taggedAs DockerComposeTag in {
      createTopicsAndSubscribe()
      create400MailgunResponse()

      val composedEmailEvent = Arbitrary.arbitrary[ComposedEmail].sample.get
      val future = composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, composedEmailEvent))
      whenReady(future) {
        case _ =>
          val failedEvents = commFailedConsumer.poll(30000).records(failedTopic).asScala.toList
          failedEvents.size shouldBe 1
          failedEvents.foreach(record => {
            val failed = record.value().getOrElse(fail("No record for ${record.key()}"))
            failed.reason shouldBe "The Email Gateway did not like our request"
          })
      }
    }

  it should "create EmailProgress event when get OK from Mailgun" taggedAs DockerComposeTag in {
    createTopicsAndSubscribe()
    createOKMailgunResponse()

    val composedEmailEvent = Arbitrary.arbitrary[ComposedEmail].sample.get
    val future = composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, composedEmailEvent))
    whenReady(future) {
      case _ =>
        val emailProgressedEvents = emailProgressedConsumer.poll(30000).records(emailProgressedTopic).asScala.toList
        emailProgressedEvents.size shouldBe 1
        emailProgressedEvents.foreach(record => {
          val emailProgressed = record.value().getOrElse(fail("No record for ${record.key()}"))
          emailProgressed.gatewayMessageId shouldBe Some("ABCDEFGHIJKL1234")
          emailProgressed.gateway shouldBe "Mailgun"
          emailProgressed.status shouldBe Queued
        })
    }
  }

  def createTopicsAndSubscribe() {
    import _root_.kafka.admin.AdminUtils
    import _root_.kafka.utils.ZkUtils

    import scala.concurrent.duration._

    val zkUtils = ZkUtils(zookeeperHosts, 30000, 5000, isZkSecurityEnabled = false)

    //Wait until kafka calls are not erroring and the service has created the composedEmailTopic
    val timeout = 10.seconds.fromNow
    var notStarted = true
    while (timeout.hasTimeLeft && notStarted) {
      try {
        notStarted = !AdminUtils.topicExists(zkUtils, composedEmailTopic)
      } catch {
        case NonFatal(ex) => Thread.sleep(100)
      }
    }
    Thread.sleep(3000L)
    if (notStarted) fail("Services did not start within 10 seconds")

    if (!AdminUtils.topicExists(zkUtils, failedTopic)) AdminUtils.createTopic(zkUtils, failedTopic, 1, 1)
    if (!AdminUtils.topicExists(zkUtils, emailProgressedTopic)) AdminUtils.createTopic(zkUtils, emailProgressedTopic, 1, 1)
    commFailedConsumer.assign(Seq(new TopicPartition(failedTopic,0)).asJava)
    emailProgressedConsumer.assign(Seq(new TopicPartition(emailProgressedTopic,0)).asJava)
  }

  def create401MailgunResponse() {
    mockServerClient.reset()
    mockServerClient.when(
      request()
        .withMethod("POST")
        .withPath(s"/v3/mailgun@email.com/messages")
    ).respond(
      response("")
          .withStatusCode(401)
    )
  }

  def create400MailgunResponse() {
    mockServerClient.reset()
    mockServerClient.when(
      request()
        .withMethod("POST")
        .withPath(s"/v3/mailgun@email.com/messages")
    ).respond(
      response("""{"message": "Some error message"}""")
        .withStatusCode(400)
    )
  }

  def createOKMailgunResponse() {
    mockServerClient.reset()
    mockServerClient.when(
      request()
        .withMethod("POST")
        .withPath(s"/v3/mailgun@email.com/messages")
    ).respond(
      response("""{"message": "Email queued", "id": "ABCDEFGHIJKL1234"}""")
        .withStatusCode(200)
    )
  }

}