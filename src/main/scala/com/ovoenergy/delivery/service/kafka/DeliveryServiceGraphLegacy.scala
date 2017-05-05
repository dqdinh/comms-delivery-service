package com.ovoenergy.delivery.service.kafka

import akka.actor.{ActorSystem, Scheduler}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{RunnableGraph, Sink}
import akka.stream.{ActorAttributes, Materializer, Supervision}
import com.ovoenergy.comms.types.ComposedEvent
import com.ovoenergy.delivery.service.domain.{DeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.logging.LoggingWithMDCLegacy
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

object DeliveryServiceGraphLegacy extends LoggingWithMDCLegacy {

  def apply[T <: ComposedEvent](consumerDeserializer: Deserializer[Option[T]],
                                issueComm: (T) => Either[DeliveryError, GatewayComm],
                                kafkaConfig: KafkaConfig,
                                retryConfig: RetryConfig,
                                consumerTopic: String,
                                sendFailedEvent: (T, DeliveryError) => Future[_],
                                sendIssuedToGatewayEvent: (T, GatewayComm) => Future[_])(
      implicit actorSystem: ActorSystem,
      materializer: Materializer,
      scheduler: Scheduler): RunnableGraph[Consumer.Control] = {

    def sendWithRetry[A](future: Future[A], composedEvent: T, errorDescription: String)(
        implicit scheduler: Scheduler) = {
      val onFailure         = (e: Throwable) => logWarn(composedEvent.metadata.traceToken, errorDescription, e)
      val futureWithRetries = Retry.retryAsync(retryConfig, onFailure)(() => future)

      futureWithRetries recover {
        case NonFatal(e) =>
          logWarn(composedEvent.metadata.traceToken,
                  s"$errorDescription even after retrying. Will give up and commit Kafka consumer offset.",
                  e)
      }
    }

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
      sendWithRetry(sendIssuedToGatewayEvent(composedEvent, gatewayComm),
                    composedEvent,
                    "Error raising IssuedForDelivery event for a successful comm")
    }

    def failure(composedEvent: T, deliveryError: DeliveryError) =
      sendWithRetry(sendFailedEvent(composedEvent, deliveryError),
                    composedEvent,
                    "Error raising Failed event for a failed comm")

    def consumerRecordToString(consumerRecord: ConsumerRecord[String, Option[T]]) = {
      s"""
           | (topic = ${consumerRecord.topic},
           |  partition = ${consumerRecord.partition},
           |  offset = ${consumerRecord.offset},
           |  ${consumerRecord.timestampType} = ${consumerRecord.timestamp},
           |  key = ${consumerRecord.key})
         """.stripMargin
    }

    val source = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(consumerTopic))
      .mapAsync(1)(msg => {
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
          .recover {
            case NonFatal(e) =>
              log.warn(s"Unhandled exception processing composed event ${consumerRecordToString(msg.record)} ", e)
              msg.committableOffset.commitScaladsl()
          }
      })
      .withAttributes(ActorAttributes.supervisionStrategy(decider))

    val sink = Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider))

    source.to(sink)
  }
}
