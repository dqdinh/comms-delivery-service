package com.ovoenergy.delivery.service.kafka.process

import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.sms.ComposedSMSV2
import com.ovoenergy.delivery.service.domain.GatewayComm
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

import scala.concurrent.{ExecutionContext, Future}

object IssuedForDeliveryEvent extends LoggingWithMDC {

  def email(publishEvent: IssuedForDeliveryV2 => Future[RecordMetadata])(
      composedEvent: ComposedEmailV2,
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
      composedEvent: ComposedSMSV2,
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