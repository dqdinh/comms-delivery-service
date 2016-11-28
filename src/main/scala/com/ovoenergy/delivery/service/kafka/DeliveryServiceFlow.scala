package com.ovoenergy.delivery.service.kafka

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, Materializer, Supervision}
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}

import scala.concurrent.Future
import scala.util.control.NonFatal

object DeliveryServiceFlow extends LoggingWithMDC {

  override def loggerName = "DeliveryServiceFlow"

  def apply[T](consumerDeserializer: Deserializer[Option[T]], issueComm: (T) => Future[_], kafkaConfig: KafkaConfig)
              (implicit actorSystem: ActorSystem, materializer: Materializer) = {

    implicit val executionContext = actorSystem.dispatcher

    val decider: Supervision.Decider = {
      case NonFatal(e) =>
        log.error("Restarting due to error", e)
        Supervision.Restart
    }

    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, consumerDeserializer)
        .withBootstrapServers(kafkaConfig.hosts)
        .withGroupId(kafkaConfig.groupId)

    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(kafkaConfig.emailComposedTopic))
      .mapAsync(1)(msg => {
        log.debug(s"Event received $msg")
        val result = msg.record.value match {
          case Some(comm) => issueComm(comm)
          case None =>
            log.error(s"Skipping event: $msg, failed to parse")
            Future.successful(())
        }
        result.map(_ => msg.committableOffset.commitScaladsl())
      })
      .to(Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider)))
      .run
  }
}