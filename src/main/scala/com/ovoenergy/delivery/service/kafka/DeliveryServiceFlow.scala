package com.ovoenergy.delivery.service.kafka

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, Materializer, Supervision}
import com.ovoenergy.comms.ComposedEmail
import com.ovoenergy.delivery.service.email.mailgun.{EmailDeliveryError, EmailProgressed}
import com.typesafe.config.Config
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object DeliveryServiceFlow {

  def apply[V](groupId: String, consumerTopic: String, deserializer: Deserializer[Try[V]], process: (V => Try[ComposedEmail]), sendMail: (ComposedEmail => Either[EmailDeliveryError, EmailProgressed]))
              (implicit actorSystem: ActorSystem, materializer: Materializer, config: Config) = {

    implicit val executionContext = actorSystem.dispatcher

    val log = LoggerFactory.getLogger(s"PipelineMetricsFlow ($consumerTopic)")

    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, deserializer)
        .withBootstrapServers(config.getString("kafka.bootstrap.servers"))
        .withGroupId(groupId)

    def fail(msg: CommittableMessage[String, Try[V]], ex: Throwable) = {
      log.error(s"Skipping event: $msg", ex)
      msg.committableOffset.commitScaladsl()
    }

    val decider: Supervision.Decider = {
      case e =>
        log.error("Restarting due to error", e)
        Supervision.Restart
    }

    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(consumerTopic))
      .map{ msg: CommittableMessage[String, Try[V]] =>
        msg.record.value match {
          case Success(event) =>
            try {
              process(event) map {
                (composedEmail: ComposedEmail) =>
                  val mail = sendMail(composedEmail)
                  mail match {
                    case Right(progressed)  => Right("Wohoo")
                    case Left(failed)       => Left("Boo!")
                  }
              } recover {
                case ex =>
                  log.error(s"Skipping event: $msg", ex)
                  msg.committableOffset.commitScaladsl()
              }
            }catch {
              case ex: Throwable =>
                fail(msg, ex)
            }
          case Failure(ex) =>
            fail(msg, ex)
        }
      }
      .to(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))
      .run()
  }
}








