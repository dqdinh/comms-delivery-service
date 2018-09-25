package com.ovoenergy.delivery.service.kafka

import cats.Show
import cats.effect.Effect
import com.ovoenergy.comms.model.LoggableEvent
import com.ovoenergy.delivery.service.Main.Record
import com.ovoenergy.delivery.service.domain.{DeliveryError, DynamoError, GatewayComm}
import com.ovoenergy.delivery.service.logging.{Loggable, LoggingWithMDC}
import com.sksamuel.avro4s.{FromRecord, SchemaFor}
import org.apache.kafka.clients.consumer.ConsumerRecord
import cats.implicits._

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

  def apply[F[_], InEvent <: LoggableEvent: SchemaFor: FromRecord: ClassTag](
      deliverComm: InEvent => F[GatewayComm],
      sendFailedEvent: (InEvent, DeliveryError) => F[Unit],
      sendIssuedToGatewayEvent: (InEvent, GatewayComm) => F[Unit])(
      implicit F: Effect[F]): Record[InEvent] => F[Unit] = { record: Record[InEvent] =>
    {

      def handleResult(composedEvent: InEvent): F[Unit] = {
        deliverComm(composedEvent)
          .flatMap { gatewayComm =>
            sendIssuedToGatewayEvent(composedEvent, gatewayComm)
          }
          .recoverWith {
            // TODO: is this logic correct?
            case d: DynamoError =>
              F.delay(logError(composedEvent, s"Failed DynamoDB operation: ${d.loggableString}, shutting down JVM")) >> F
                .raiseError(new RuntimeException(d.description))
            case err: DeliveryError =>
              F.delay(logWarn(composedEvent, s"Unable to send comm: ${err.loggableString}")) >> sendFailedEvent(
                composedEvent,
                err)
          }
      }

      F.delay(logInfo(record, s"Consumed ${record.show}")) >> {
        record.value match {
          case Some(event) => handleResult(event)
          case None        => F.delay(log.error(s"Skipping event: ${record.show}, failed to parse")) >> F.pure(())
        }
      }

    }
  }
}
