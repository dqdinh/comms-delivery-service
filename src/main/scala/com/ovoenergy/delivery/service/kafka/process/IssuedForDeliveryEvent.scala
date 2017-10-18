package com.ovoenergy.delivery.service.kafka.process

import com.ovoenergy.comms.model.email.{ComposedEmailV2, ComposedEmailV3}
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.comms.model.sms.{ComposedSMSV2, ComposedSMSV3}
import com.ovoenergy.delivery.service.domain.GatewayComm
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

import scala.concurrent.{ExecutionContext, Future}

object IssuedForDeliveryEvent extends LoggingWithMDC {

  def email(publishEvent: IssuedForDeliveryV2 => Future[RecordMetadata])(
      composedEvent: ComposedEmailV3,
      gatewayComm: GatewayComm)(implicit ec: ExecutionContext): Future[RecordMetadata] = {
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
      record
    })
  }

  def sms(publishEvent: IssuedForDeliveryV2 => Future[RecordMetadata])(
      composedEvent: ComposedSMSV3,
      gatewayComm: GatewayComm)(implicit ec: ExecutionContext): Future[RecordMetadata] = {
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
      record
    })
  }

  def print(publishEvent: IssuedForDeliveryV2 => Future[RecordMetadata])(
      composedEvent: ComposedPrint,
      gatewayComm: GatewayComm)(implicit ec: ExecutionContext): Future[RecordMetadata] = {
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
      record
    })
  }
}
