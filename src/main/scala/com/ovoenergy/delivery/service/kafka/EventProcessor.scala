package com.ovoenergy.delivery.service.kafka

import cats.Show
import cats.effect.{Async, Effect}
import cats.syntax.all._
import com.ovoenergy.comms.model.LoggableEvent
import com.ovoenergy.delivery.config.KafkaAppConfig
import com.ovoenergy.delivery.service.Main.Record
import com.ovoenergy.delivery.service.domain.{DeliveryError, DynamoError, GatewayComm}
import com.ovoenergy.delivery.service.logging.{Loggable, LoggingWithMDC}
import com.sksamuel.avro4s.{FromRecord, SchemaFor}
import org.apache.kafka.clients.consumer.ConsumerRecord
import scala.reflect.ClassTag

object EventProcessor extends LoggingWithMDC {

  implicit def consumerRecordShow[K, V]: Show[ConsumerRecord[K, V]] = Show.show[ConsumerRecord[K, V]] { record =>
    s"kafkaTopic: ${record.topic()}, kafkaPartition: ${record.partition()}, kafkaOffset: ${record.offset()}"
  }

  implicit def consumerRecordLoggable[K, V]: Loggable[ConsumerRecord[K, V]] =
    Loggable.instance(
      record =>
        Map("kafkaTopic"     -> record.topic(),
            "kafkaPartition" -> record.partition().toString,
            "kafkaOffset"    -> record.offset().toString))

  def apply[F[_]: Effect, InEvent <: LoggableEvent: SchemaFor: FromRecord: ClassTag](
      deliverComm: InEvent => F[Either[DeliveryError, GatewayComm]],
      sendFailedEvent: (InEvent, DeliveryError) => F[_],
      sendIssuedToGatewayEvent: (InEvent, GatewayComm) => F[_]): Record[InEvent] => F[Unit] = {

    def result: Record[InEvent] => F[Unit] = (record: Record[InEvent]) => {
      Async[F].delay(logInfo(record, s"Consumed ${record.show}")) >> (record.value match {
        case Some(composedEvent) =>
          deliverComm(composedEvent) flatMap {
            case Right(gatewayComm) =>
              sendIssuedToGatewayEvent(composedEvent, gatewayComm).void

            case Left(error: DynamoError) => {
              logError(composedEvent, "Failed DynamoDB operation, shutting down JVM")
              Async[F].raiseError(new RuntimeException(error.description))
            }
            case Left(deliveryError) =>
              logWarn(composedEvent, s"Unable to send comm due to $deliveryError")
              sendFailedEvent(composedEvent, deliveryError).void
          }
        case None =>
          log.error(s"Skipping event: ${record.show}, failed to parse")
          Async[F].pure(())

      })
    }

    result
  }
}
