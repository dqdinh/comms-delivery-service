package com.ovoenergy.delivery.service.kafka.process.email

import com.ovoenergy.comms.model.EmailStatus.Queued
import com.ovoenergy.comms.model.{EmailProgressed, Metadata}
import com.ovoenergy.comms.types.ComposedEvent
import com.ovoenergy.delivery.service.domain.GatewayComm
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

import scala.concurrent.{ExecutionContext, Future}

object EmailProgressedEvent extends LoggingWithMDC {

  def send(publishEvent: (EmailProgressed) => Future[RecordMetadata])(comm: ComposedEvent, gatewayComm: GatewayComm)(implicit ec: ExecutionContext): Future[RecordMetadata] = {
    val event = EmailProgressed(
      metadata = Metadata.fromSourceMetadata("delivery-service", comm.metadata),
      internalMetadata = comm.internalMetadata,
      status = Queued,
      gateway = gatewayComm.gateway,
      gatewayMessageId = Some(gatewayComm.id)
    )

    publishEvent(event).map(record => {
      logInfo(event.metadata.traceToken, s"Publishing EmailProgressed event: ${event.status} - ${event.gateway} - ${event.gatewayMessageId} - ${record.partition}/${record.offset}")
      record
    })
  }
}
