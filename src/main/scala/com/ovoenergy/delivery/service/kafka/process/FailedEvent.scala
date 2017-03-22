package com.ovoenergy.delivery.service.kafka.process

import com.ovoenergy.comms.model.{Failed, Metadata}
import com.ovoenergy.comms.types.ComposedEvent
import com.ovoenergy.delivery.service.domain.DeliveryError
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

import scala.concurrent.{ExecutionContext, Future}

object FailedEvent extends LoggingWithMDC {

  def send(publishEvent: (Failed) => Future[RecordMetadata])(comm: ComposedEvent, deliveryError: DeliveryError)(
      implicit ec: ExecutionContext): Future[RecordMetadata] = {
    val event = Failed(
      metadata = Metadata.fromSourceMetadata("delivery-service", comm.metadata),
      internalMetadata = comm.internalMetadata,
      reason = deliveryError.description,
      errorCode = deliveryError.errorCode
    )

    publishEvent(event).map(record => {
      logInfo(event.metadata.traceToken,
              s"Publishing Failed event: ${event.errorCode} - ${event.reason} - ${record.partition}/${record.offset}")
      record
    })
  }

}
