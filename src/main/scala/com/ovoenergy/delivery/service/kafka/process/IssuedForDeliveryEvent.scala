package com.ovoenergy.delivery.service.kafka.process

import cats.Functor
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.delivery.service.domain.{GatewayComm, builders}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import org.apache.kafka.clients.producer.RecordMetadata

object IssuedForDeliveryEvent extends LoggingWithMDC {

  def email[F[_]: Functor](publishEvent: IssuedForDeliveryV3 => F[RecordMetadata])(
      composedEvent: ComposedEmailV4,
      gatewayComm: GatewayComm): F[Unit] = {
    val event = IssuedForDeliveryV3(
      metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
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

  def sms[F[_]: Functor](publishEvent: IssuedForDeliveryV3 => F[RecordMetadata])(composedEvent: ComposedSMSV4,
                                                                                 gatewayComm: GatewayComm): F[Unit] = {
    val event = IssuedForDeliveryV3(
      metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
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

  def print[F[_]: Async](publishIssued: IssuedForDeliveryV3 => F[RecordMetadata],
                         publishFeedback: Feedback => F[RecordMetadata]) = {
    (composedEvent: ComposedPrintV2, gatewayComm: GatewayComm) =>
      val issuedForDeliveryEvent = IssuedForDeliveryV3(
        metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
        internalMetadata = composedEvent.internalMetadata,
        channel = gatewayComm.channel,
        gateway = gatewayComm.gateway,
        gatewayMessageId = gatewayComm.id
      )

      val feedbackEvent = builders.buildfeedbackFromPrint(composedEvent, None, FeedbackOptions.Delivered)

      for {
        rm1 <- publishIssued(issuedForDeliveryEvent)
        _ <- {
          Async[F].delay(
            logInfo(
              issuedForDeliveryEvent,
              s"Published IssuedForDelivery event: ${issuedForDeliveryEvent.gateway} - ${issuedForDeliveryEvent.gatewayMessageId} - ${rm1.partition}/${rm1.offset}"
            ))
        }
        rm2 <- publishFeedback(feedbackEvent)
        _ <- Async[F].delay(
          logInfo(
            feedbackEvent,
            s"Published Feedback event: ${issuedForDeliveryEvent.gateway} - ${issuedForDeliveryEvent.gatewayMessageId} - ${rm2.partition}/${rm2.offset}"
          ))
      } yield Seq(rm1, rm2)
  }
}
