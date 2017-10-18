package com.ovoenergy.delivery.service.kafka.process

import com.ovoenergy.comms.model.email.{ComposedEmailV2, ComposedEmailV3}
import com.ovoenergy.comms.model.sms.{ComposedSMSV2, ComposedSMSV3}
import com.ovoenergy.comms.model.{Failed, FailedV2, MetadataV2}
import com.ovoenergy.delivery.service.domain.DeliveryError
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

import scala.concurrent.{ExecutionContext, Future}

object FailedEvent extends LoggingWithMDC {

  def email(publishEvent: FailedV2 => Future[RecordMetadata])(
      composedEvent: ComposedEmailV3,
      deliveryError: DeliveryError)(implicit ec: ExecutionContext): Future[RecordMetadata] = {
    val event = FailedV2(
      metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEvent.metadata),
      internalMetadata = composedEvent.internalMetadata,
      reason = deliveryError.description,
      errorCode = deliveryError.errorCode
    )

    publishEvent(event).map(record => {
      logInfo(event,
              s"Publishing Failed event: ${event.errorCode} - ${event.reason} - ${record.partition}/${record.offset}")
      record
    })
  }

  def sms(publishEvent: FailedV2 => Future[RecordMetadata])(
      composedEvent: ComposedSMSV3,
      deliveryError: DeliveryError)(implicit ec: ExecutionContext): Future[RecordMetadata] = {
    val event = FailedV2(
      metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEvent.metadata),
      internalMetadata = composedEvent.internalMetadata,
      reason = deliveryError.description,
      errorCode = deliveryError.errorCode
    )

    publishEvent(event).map(record => {
      logInfo(event,
              s"Publishing Failed event: ${event.errorCode} - ${event.reason} - ${record.partition}/${record.offset}")
      record
    })
  }

}
