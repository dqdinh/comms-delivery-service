package com.ovoenergy.delivery.service.kafka

import cats.Show
import cats.syntax.all._
import akka.actor.Scheduler
import cats.effect.{Async, Effect}
import com.ovoenergy.comms.model.LoggableEvent
import com.ovoenergy.delivery.config.KafkaAppConfig
import com.ovoenergy.delivery.service.Main.Record
import com.ovoenergy.delivery.service.domain.{DeliveryError, DynamoError, GatewayComm}
import com.ovoenergy.delivery.service.logging.{Loggable, LoggingWithMDC}
import com.ovoenergy.delivery.service.util.Retry
import com.sksamuel.avro4s.{FromRecord, SchemaFor}
import org.apache.kafka.clients.consumer.ConsumerRecord

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.control.NonFatal

object EventProcessor extends LoggingWithMDC {

  implicit def consumerRecordShow[K, V]: Show[ConsumerRecord[K, V]] = Show.show[ConsumerRecord[K, V]] { record =>
    s"kafkaTopic: ${record.topic()}, kafkaPartition: ${record.partition()}, kafkaOffset: ${record.offset()}"
  }

  implicit def consumerRecordLoggable[K, V]: Loggable[ConsumerRecord[K, V]] =
    Loggable.instance(
      record =>
        Map("kafkaTopic" -> record.topic(),
          "kafkaPartition" -> record.partition().toString,
          "kafkaOffset" -> record.offset().toString))

  def apply[F[_]: Effect, InEvent <: LoggableEvent: SchemaFor: FromRecord: ClassTag](
       deliverComm: (InEvent) => Either[DeliveryError, GatewayComm],
       sendFailedEvent: (InEvent, DeliveryError) => Future[_],
       sendIssuedToGatewayEvent: (InEvent, GatewayComm) => Future[_])(implicit ec: ExecutionContext, config: KafkaAppConfig, scheduler: Scheduler): Record[InEvent] => F[Unit] = {

    val retryConfig = Retry.exponentialDelay(config.retry)

    def sendWithRetry[A](future: Future[A], inputEvent: InEvent, errorDescription: String)(implicit scheduler: Scheduler) = {
      val onFailure         = (e: Throwable) => logWarn(inputEvent, errorDescription, e)
      val futureWithRetries = Retry.retryAsync(retryConfig, onFailure)(() => future)

      futureWithRetries recover {
        case NonFatal(e) =>
          logWarn(inputEvent,
            s"$errorDescription even after retrying. Will give up and commit Kafka consumer offset.",
            e)
      }
    }

    def success(composedEvent: InEvent, gatewayComm: GatewayComm) = {
      sendWithRetry(sendIssuedToGatewayEvent(composedEvent, gatewayComm),
        composedEvent,
        "Error raising IssuedForDelivery event for a successful comm")
    }

    def failure(composedEvent: InEvent, deliveryError: DeliveryError) = {
      logWarn(composedEvent, s"Unable to send comm due to $deliveryError")
      sendWithRetry(sendFailedEvent(composedEvent, deliveryError),
        composedEvent,
        "Error raising Failed event for a failed comm")
    }

    def result[F[_]: Effect]: Record[InEvent] => F[Unit] = (record: Record[InEvent]) => {
      Async[F].delay(logInfo(record, s"Consumed ${record.show}")) >> (record.value match {
        case Some(composedEvent) => {
          deliverComm(composedEvent) match {
            case Right(gatewayComm) =>
              success(composedEvent, gatewayComm)
              Async[F].pure(())
            case Left(error: DynamoError) => {
              logError(composedEvent, "Failed DynamoDB operation, shutting down JVM")
              Async[F].pure(())
            }
            case Left(deliveryError) =>
              failure(composedEvent, deliveryError)
              Async[F].pure(())
          }
        }
        case None =>
          log.error(s"Skipping event: ${record.show}, failed to parse")
          Async[F].pure(())

      })
    }

    result[F]
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
