package com.ovoenergy.delivery.service

import java.time.LocalDateTime
import java.util.UUID

import cakesolutions.kafka.KafkaConsumer.{Conf => KafkaConsumerConf}
import cakesolutions.kafka.KafkaProducer.{Conf => KafkaProducerConf}
import cakesolutions.kafka.{KafkaConsumer, KafkaProducer}
import com.ovoenergy.comms.ComposedEmail
import com.ovoenergy.comms.EmailStatus.Queued
import com.ovoenergy.delivery.service.Serialization._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.mockserver.client.server.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalacheck.Arbitrary
import org.scalacheck.Shapeless._
import org.scalatest._
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
  with BeforeAndAfterAll
  with OneInstancePerTest {

  object DockerComposeTag extends Tag("DockerComposeTag")

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  implicit val config: PatienceConfig = PatienceConfig(Span(60, Seconds))

  val kafkaHosts = "localhost:29092"
  val zookeeperHosts = "localhost:32181"

  val consumerGroup = Random.nextString(10)
  val composedEmailProducer = KafkaProducer(KafkaProducerConf(new StringSerializer, composedEmailSerializer, kafkaHosts))
  val commFailedConsumer = KafkaConsumer(KafkaConsumerConf(new StringDeserializer, failedDeserializer, kafkaHosts, consumerGroup))
  val emailProgressedConsumer = KafkaConsumer(KafkaConsumerConf(new StringDeserializer, emailProgressedDeserializer, kafkaHosts, consumerGroup))

  val failedTopic = "comms.failed"
  val composedEmailTopic = "comms.composed.email"
  val emailProgressedTopic = "comms.progressed.email"

  val mockServerClient = new MockServerClient("localhost", 1080)

  behavior of "producer"

    it should "create Failed event when authentication fails with Mailgun" taggedAs DockerComposeTag in {
      createTopicsAndSubscribe()
      create401MailgunResponse()

      forAll(minSuccessful(1)) { (msg: ComposedEmail) =>
        val future = composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, msg))
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
    }

    it should "create Failed event when get bad request from Mailgun" taggedAs DockerComposeTag in {
      createTopicsAndSubscribe()
      create400MailgunResponse()

      forAll(minSuccessful(1)) { (msg: ComposedEmail) =>
        val future = composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, msg))
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
    }

  it should "create EmailProgress event when get OK from Mailgun" taggedAs DockerComposeTag in {
    createTopicsAndSubscribe()
    createOKMailgunResponse()

    forAll(minSuccessful(1)) { (msg: ComposedEmail) =>
      val future = composedEmailProducer.send(new ProducerRecord[String, ComposedEmail](composedEmailTopic, msg))
      whenReady(future) {
        case _ =>
          val emailProgressedEvents = emailProgressedConsumer.poll(30000).records(emailProgressedTopic).asScala.toList
          emailProgressedEvents.size shouldBe 1
          emailProgressedEvents.foreach(record => {
            val emailProgressed = record.value().getOrElse(fail("No record for ${record.key()}"))
            emailProgressed.gatewayMessageId shouldBe "ABCDEFGHIJKL1234"
            emailProgressed.gateway shouldBe "Mailgun"
            emailProgressed.status shouldBe Queued
          })
      }
    }
  }

  def createTopicsAndSubscribe() {
    import _root_.kafka.admin.AdminUtils
    import _root_.kafka.utils.ZkUtils
    import scala.concurrent.duration._

    val zkUtils = ZkUtils(zookeeperHosts, 30000, 5000, isZkSecurityEnabled = false)

    val timeout = 10.seconds.fromNow
    var kafkaNotStarted = true
    while (timeout.hasTimeLeft && kafkaNotStarted) {
      try {
        AdminUtils.topicExists(zkUtils, failedTopic)
        kafkaNotStarted = false
      } catch {
        case NonFatal(ex) => Thread.sleep(100)
      }
    }
    if (kafkaNotStarted) fail("Kafka did not start within 10 seconds")
    if (!AdminUtils.topicExists(zkUtils, failedTopic)) AdminUtils.createTopic(zkUtils, failedTopic, 1, 1)
    if (!AdminUtils.topicExists(zkUtils, emailProgressedTopic)) AdminUtils.createTopic(zkUtils, emailProgressedTopic, 1, 1)

    commFailedConsumer.subscribe(Seq(failedTopic).asJava)
    emailProgressedConsumer.subscribe(Seq(emailProgressedTopic).asJava)

    //Poll, helps topics stabilize
    commFailedConsumer.poll(1000)
    emailProgressedConsumer.poll(1000)
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