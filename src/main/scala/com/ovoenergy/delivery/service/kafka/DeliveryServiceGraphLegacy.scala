package com.ovoenergy.delivery.service.kafka

import akka.actor.{ActorSystem, Scheduler}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{RunnableGraph, Sink}
import akka.stream.{ActorAttributes, Materializer, Supervision}
import com.ovoenergy.comms.model.LoggableEvent
import com.ovoenergy.delivery.service.domain.{DeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.kafka.domain.KafkaConfig
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.util.Retry.RetryConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

object DeliveryServiceGraphLegacy extends LoggingWithMDC {

  def apply[LegacyEvent, NewEvent <: LoggableEvent](consumerDeserializer: Deserializer[Option[LegacyEvent]],
                                                    convertEvent: LegacyEvent => NewEvent,
                                                    issueComm: (NewEvent) => Either[DeliveryError, GatewayComm],
                                                    kafkaConfig: KafkaConfig,
                                                    retryConfig: RetryConfig,
                                                    consumerTopic: String,
                                                    sendFailedEvent: (NewEvent, DeliveryError) => Future[_],
                                                    sendIssuedToGatewayEvent: (NewEvent, GatewayComm) => Future[_])(
      implicit actorSystem: ActorSystem,
      materializer: Materializer,
      scheduler: Scheduler): RunnableGraph[Consumer.Control] = {

    def sendWithRetry[A](future: Future[A], inputEvent: NewEvent, errorDescription: String)(
        implicit scheduler: Scheduler) = {
      val onFailure         = (e: Throwable) => logWarn(inputEvent, errorDescription, e)
      val futureWithRetries = Retry.retryAsync(retryConfig, onFailure)(() => future)

      futureWithRetries recover {
        case NonFatal(e) =>
          logWarn(inputEvent,
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

    def success(composedEvent: NewEvent, gatewayComm: GatewayComm) = {
      sendWithRetry(sendIssuedToGatewayEvent(composedEvent, gatewayComm),
                    composedEvent,
                    "Error raising IssuedForDelivery event for a successful comm")
    }

    def failure(composedEvent: NewEvent, deliveryError: DeliveryError) = {
      logWarn(composedEvent, s"Unable to send comm due to $deliveryError")
      sendWithRetry(sendFailedEvent(composedEvent, deliveryError),
                    composedEvent,
                    "Error raising Failed event for a failed comm")
    }

    val source = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(consumerTopic))
      .mapAsync(1) { msg =>
        log.debug(s"Event received $msg")
        val result = msg.record.value match {
          case Some(legacyEvent) =>
            val composedEvent = convertEvent(legacyEvent)
            issueComm(composedEvent) match {
              case Right(gatewayComm) =>
                success(composedEvent, gatewayComm)
              case Left(deliveryError) =>
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
      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))

    val sink = Sink.ignore.withAttributes(ActorAttributes.supervisionStrategy(decider))

    source.to(sink)
  }

  private def consumerRecordToString(consumerRecord: ConsumerRecord[String, _]) = {
    s"""
       | (topic = ${consumerRecord.topic},
       |  partition = ${consumerRecord.partition},
       |  offset = ${consumerRecord.offset},
       |  ${consumerRecord.timestampType} = ${consumerRecord.timestamp},
       |  key = ${consumerRecord.key})
         """.stripMargin
  }

}
