package com.ovoenergy.delivery.service.kafka.process

import cats.Functor
import cats.syntax.functor._
import com.ovoenergy.comms.model.email.ComposedEmailV3
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.comms.model.sms.ComposedSMSV3
import com.ovoenergy.comms.model.{FailedV2, MetadataV2}
import com.ovoenergy.delivery.service.domain.DeliveryError
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

object FailedEvent extends LoggingWithMDC {

  def email[F[_]: Functor](publishEvent: FailedV2 => F[RecordMetadata])(composedEvent: ComposedEmailV3,
                                                                        deliveryError: DeliveryError): F[Unit] = {

    val event = FailedV2(
      metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEvent.metadata),
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

  def sms[F[_]: Functor](publishEvent: FailedV2 => F[RecordMetadata])(composedEvent: ComposedSMSV3,
                                                                      deliveryError: DeliveryError): F[Unit] = {
    val event = FailedV2(
      metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEvent.metadata),
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

  def print[F[_]: Functor](publishEvent: FailedV2 => F[RecordMetadata])(composedEvent: ComposedPrint,
                                                                        deliveryError: DeliveryError): F[Unit] = {

    val event = FailedV2(
      metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEvent.metadata),
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
