package com.ovoenergy.delivery.service.kafka.process

import com.ovoenergy.comms.model.{IssuedForDelivery, Metadata}
import com.ovoenergy.comms.types.ComposedEvent
import com.ovoenergy.delivery.service.domain.GatewayComm
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

import scala.concurrent.{ExecutionContext, Future}

object IssuedForDeliveryEvent extends LoggingWithMDC {

  def send(publishEvent: (IssuedForDelivery) => Future[RecordMetadata])(comm: ComposedEvent, gatewayComm: GatewayComm)(
      implicit ec: ExecutionContext): Future[RecordMetadata] = {
    val event = IssuedForDelivery(
      metadata = Metadata.fromSourceMetadata("delivery-service", comm.metadata),
      internalMetadata = comm.internalMetadata,
      channel = gatewayComm.channel,
      gateway = gatewayComm.gateway,
      gatewayMessageId = gatewayComm.id
    )

    publishEvent(event).map(record => {
      logInfo(
        event.metadata.traceToken,
        s"Published IssuedForDelivery event: ${event.gateway} - ${event.gatewayMessageId} - ${record.partition}/${record.offset}")
      record
    })
  }
}
