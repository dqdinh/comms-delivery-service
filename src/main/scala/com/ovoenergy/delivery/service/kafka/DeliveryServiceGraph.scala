package com.ovoenergy.delivery.service.kafka

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{RunnableGraph, Sink}
import akka.stream.{ActorAttributes, Materializer, Supervision}
import com.ovoenergy.comms.types.ComposedEvent
import com.ovoenergy.delivery.service.domain.{DeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}

import scala.concurrent.Future
import scala.util.control.NonFatal

object DeliveryServiceGraph extends LoggingWithMDC {

  def apply[T <: ComposedEvent](consumerDeserializer: Deserializer[Option[T]],
                                issueComm: (T) => Either[DeliveryError, GatewayComm],
                                kafkaConfig: KafkaConfig,
                                consumerTopic: String,
                                sendFailedEvent: (T, DeliveryError) => Future[_],
                                sendCommProgressedEvent: (T, GatewayComm) => Future[_],
                                sendIssuedToGatewayEvent: (T, GatewayComm) => Future[_])(
      implicit actorSystem: ActorSystem,
      materializer: Materializer): RunnableGraph[Consumer.Control] = {

    implicit val executionContext = actorSystem.dispatcher

    val decider: Supervision.Decider = {
      case NonFatal(e) =>
        log.error("Stopping due to error", e)
        Supervision.Stop
    }

    val consumerSettings =
      ConsumerSettings(actorSystem, new StringDeserializer, consumerDeserializer)
        .withBootstrapServers(kafkaConfig.hosts)
        .withGroupId(kafkaConfig.groupId)

    def success(composedEvent: T, gatewayComm: GatewayComm) = {
      val futures = List(
        sendIssuedToGatewayEvent(composedEvent, gatewayComm),
        sendCommProgressedEvent(composedEvent, gatewayComm)
      )
      Future.sequence(futures).recover {
        case NonFatal(e) =>
          logWarn(composedEvent.metadata.traceToken,
                  "Error raising events for a successful comm, offset will be committed",
                  e)
      }
    }

    def failure(composedEvent: T, deliveryError: DeliveryError) = {
      sendFailedEvent(composedEvent, deliveryError).recover {
        case NonFatal(e) =>
          logWarn(composedEvent.metadata.traceToken,
                  "Error raising event for a failed comm, offset will be committed",
                  e)
      }
    }

    val source = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(consumerTopic))
      .mapAsync(1)(f = msg => {
        log.debug(s"Event received $msg")
        val result = msg.record.value match {
          case Some(composedEvent) =>
            issueComm(composedEvent) match {
              case Right(gatewayComm) =>
                success(composedEvent, gatewayComm)
              case Left(deliveryError) =>
                logWarn(composedEvent.metadata.traceToken, s"Unable to send comm due to $deliveryError")
                failure(composedEvent, deliveryError)
            }
          case None =>
            log.error(s"Skipping event: $msg, failed to parse")
            Future.successful(())
        }
        result
          .flatMap(_ => msg.committableOffset.commitScaladsl())
          .recover { case NonFatal(_) => msg.committableOffset.commitScaladsl() }
      })
      .withAttributes(ActorAttributes.supervisionStrategy(decider))

    val sink = Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider))

    source.to(sink)
  }
}
