package com.ovoenergy.delivery.service.kafka.process

import cats.Functor
import cats.syntax.functor._

import com.ovoenergy.comms.model.email.ComposedEmailV3
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.comms.model.sms.ComposedSMSV3
import com.ovoenergy.delivery.service.domain.GatewayComm
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

object IssuedForDeliveryEvent extends LoggingWithMDC {

  def email[F[_]: Functor](publishEvent: IssuedForDeliveryV2 => F[RecordMetadata])(
      composedEvent: ComposedEmailV3,
      gatewayComm: GatewayComm): F[Unit] = {
    val event = IssuedForDeliveryV2(
      metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEvent.metadata),
      internalMetadata = composedEvent.internalMetadata,
      channel = gatewayComm.channel,
      gateway = gatewayComm.gateway,
      gatewayMessageId = gatewayComm.id
    )

    publishEvent(event).map(record => {
      logInfo(
        event,
        s"Published IssuedForDelivery event: ${event.gateway} - ${event.gatewayMessageId} - ${record.partition}/${record.offset}")
    })
  }

  def sms[F[_]: Functor](publishEvent: IssuedForDeliveryV2 => F[RecordMetadata])(composedEvent: ComposedSMSV3,
                                                                                 gatewayComm: GatewayComm): F[Unit] = {
    val event = IssuedForDeliveryV2(
      metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEvent.metadata),
      internalMetadata = composedEvent.internalMetadata,
      channel = gatewayComm.channel,
      gateway = gatewayComm.gateway,
      gatewayMessageId = gatewayComm.id
    )

    publishEvent(event).map(record => {
      logInfo(
        event,
        s"Published IssuedForDelivery event: ${event.gateway} - ${event.gatewayMessageId} - ${record.partition}/${record.offset}")
    })
  }

  def print[F[_]: Functor](publishEvent: IssuedForDeliveryV2 => F[RecordMetadata])(
      composedEvent: ComposedPrint,
      gatewayComm: GatewayComm): F[Unit] = {
    val event = IssuedForDeliveryV2(
      metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEvent.metadata),
      internalMetadata = composedEvent.internalMetadata,
      channel = gatewayComm.channel,
      gateway = gatewayComm.gateway,
      gatewayMessageId = gatewayComm.id
    )

    publishEvent(event).map(record => {
      logInfo(
        event,
        s"Published IssuedForDelivery event: ${event.gateway} - ${event.gatewayMessageId} - ${record.partition}/${record.offset}")
    })
  }
}
