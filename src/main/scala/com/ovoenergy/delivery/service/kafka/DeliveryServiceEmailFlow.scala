package com.ovoenergy.delivery.service.kafka

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, Materializer, Supervision}
import com.ovoenergy.comms.{ComposedEmail, Failed}
import com.ovoenergy.delivery.service.email.mailgun.EmailProgressed
import com.typesafe.config.Config
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Deserializer
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object DeliveryServiceEmailFlow {

  def apply(consumerSettings: ConsumerSettings[String, Try[ComposedEmail]], consumerTopic: String, deserializer: Deserializer[Try[ComposedEmail]], sendMail: (ComposedEmail => Either[Failed, EmailProgressed]), kafkaProducers: KafkaProducers)
              (implicit actorSystem: ActorSystem, materializer: Materializer, config: Config) = {

    implicit val executionContext = actorSystem.dispatcher

    val failedPublishTopic: String          = config.getString("topics.failed")
    val emailProgressedPublishTopic: String = config.getString("topics.composed.email")

    val log = LoggerFactory.getLogger(s"DeliveryServiceFlow ($consumerTopic)")

    val decider: Supervision.Decider = {
      case e =>
        log.error("Restarting due to error", e)
        Supervision.Restart
    }

    def sendAndProcessComm(msg: CommittableMessage[String, Try[ComposedEmail]], composedEmail: ComposedEmail) = {
      sendMail(composedEmail) match {
        case Left(failed)      => kafkaProducers.failedEmailProducer.send(new ProducerRecord[String, Try[Failed]](failedPublishTopic, Try(failed)))
        case Right(progressed) => kafkaProducers.emailProgressedProducer.send(new ProducerRecord[String, Try[EmailProgressed]](emailProgressedPublishTopic, Try(progressed)))
      }
    }

    def fail(msg: CommittableMessage[String, Try[ComposedEmail]], ex: Throwable) = {
      log.error(s"Skipping event: $msg", ex)
      msg.committableOffset.commitScaladsl()
    }

    def extractAndSend(msg: CommittableMessage[String, Try[ComposedEmail]]) = {
      msg.record.value match {
        case Success(event) =>
          try {
            (composedEmail: ComposedEmail) =>
              sendAndProcessComm(msg, composedEmail)
          }catch {
            case ex: Throwable =>
              fail(msg, ex)
          }
        case Failure(ex) =>
          fail(msg, ex)
      }
    }

    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(consumerTopic))
      .map(extractAndSend)
      .to(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))
      .run
  }
}




