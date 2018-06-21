package com.ovoenergy.delivery.service.kafka.process

import cats.Functor
import cats.syntax.functor._
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.comms.model.{FailedV3, MetadataV3}
import com.ovoenergy.delivery.service.domain.DeliveryError
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

object FailedEvent extends LoggingWithMDC {

  def email[F[_]: Functor](publishEvent: FailedV3 => F[RecordMetadata])(composedEvent: ComposedEmailV4,
                                                                        deliveryError: DeliveryError): F[Unit] = {

    val event = FailedV3(
      metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
      internalMetadata = composedEvent.internalMetadata,
      reason = deliveryError.description,
      errorCode = deliveryError.errorCode
    )

    publishEvent(event).map(record => {
      logInfo(event,
              s"Publishing Failed event: ${event.errorCode} - ${event.reason} - ${record.partition}/${record.offset}")
      ()
    })
  }

  def sms[F[_]: Functor](publishEvent: FailedV3 => F[RecordMetadata])(composedEvent: ComposedSMSV4,
                                                                      deliveryError: DeliveryError): F[Unit] = {
    val event = FailedV3(
      metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
      internalMetadata = composedEvent.internalMetadata,
      reason = deliveryError.description,
      errorCode = deliveryError.errorCode
    )

    publishEvent(event).map(record => {
      logInfo(event,
              s"Publishing Failed event: ${event.errorCode} - ${event.reason} - ${record.partition}/${record.offset}")
      ()
    })
  }

  def print[F[_]: Functor](publishEvent: FailedV3 => F[RecordMetadata])(composedEvent: ComposedPrintV2,
                                                                        deliveryError: DeliveryError): F[Unit] = {

    val event = FailedV3(
      metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
      internalMetadata = composedEvent.internalMetadata,
      reason = deliveryError.description,
      errorCode = deliveryError.errorCode
    )

    publishEvent(event).map(record => {
      logInfo(event,
              s"Publishing Failed event: ${event.errorCode} - ${event.reason} - ${record.partition}/${record.offset}")
      ()
    })
  }
}
