package com.ovoenergy.delivery.service.kafka

import akka.actor.{ActorSystem, Scheduler}
import akka.kafka.scaladsl.Consumer
import akka.kafka.Subscriptions
import akka.stream.scaladsl.{RunnableGraph, Sink}
import akka.stream.{ActorAttributes, Materializer, Supervision}
import com.ovoenergy.comms.helpers.Topic
import com.ovoenergy.comms.model.LoggableEvent
import com.ovoenergy.delivery.config.KafkaAppConfig
import com.ovoenergy.delivery.service.domain.{DeliveryError, DynamoError, GatewayComm}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.util.Retry
import com.ovoenergy.delivery.service.ErrorHandling._
import com.sksamuel.avro4s.{FromRecord, SchemaFor}
import org.apache.kafka.clients.consumer.ConsumerRecord

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import scala.util.control.NonFatal

object DeliveryServiceGraph extends LoggingWithMDC {

  def apply[T <: LoggableEvent: SchemaFor: FromRecord: ClassTag](
      topic: Topic[T],
      deliverComm: (T) => Either[DeliveryError, GatewayComm],
      sendFailedEvent: (T, DeliveryError) => Future[_],
      sendIssuedToGatewayEvent: (T, GatewayComm) => Future[_])(
      implicit actorSystem: ActorSystem,
      config: KafkaAppConfig,
      materializer: Materializer,
      scheduler: Scheduler): RunnableGraph[Consumer.Control] = {

    val retryConfig = Retry.exponentialDelay(config.retry)

    log.error(s"${topic.name} started successfully")
    def sendWithRetry[A](future: Future[A], inputEvent: T, errorDescription: String)(implicit scheduler: Scheduler) = {
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

    val consumerSettings = exitAppOnFailure(topic.consumerSettings, topic.name)

    def success(composedEvent: T, gatewayComm: GatewayComm) = {
      sendWithRetry(sendIssuedToGatewayEvent(composedEvent, gatewayComm),
                    composedEvent,
                    "Error raising IssuedForDelivery event for a successful comm")
    }

    def failure(composedEvent: T, deliveryError: DeliveryError) = {
      logWarn(composedEvent, s"Unable to send comm due to $deliveryError")
      sendWithRetry(sendFailedEvent(composedEvent, deliveryError),
                    composedEvent,
                    "Error raising Failed event for a failed comm")
    }

    val source = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic.name))
      .mapAsync(1) { msg =>
        log.debug(s"Event received $msg")

        val result = msg.record.value match {
          case Some(composedEvent) => {
            deliverComm(composedEvent) match {
              case Right(gatewayComm) =>
                success(composedEvent, gatewayComm)
              case Left(error: DynamoError) => {
                logError(composedEvent, "Failed DynamoDB operation, shutting down JVM")
                sys.exit(1)
              }
              case Left(deliveryError) =>
                failure(composedEvent, deliveryError)
            }
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
