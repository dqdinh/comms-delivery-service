package com.ovoenergy.delivery.service.kafka.process

import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.flatMap._
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.domain.{BuildFailed, BuildFeedback, DeliveryError}
import com.ovoenergy.delivery.service.logging.{Loggable, LoggingWithMDC}
import org.apache.kafka.clients.producer.RecordMetadata

object FailedEvent extends LoggingWithMDC {
  implicit private val loggableRecordMetadata = Loggable.instance[RecordMetadata] { rm =>
    Map("kafkaTopic" -> rm.topic(), "kafkaPartition" -> rm.partition().toString, "kafkaOffset" -> rm.offset().toString)
  }

  def apply[F[_]: Async, Event: BuildFailed: BuildFeedback](publishFailedEventLegacy: FailedV3 => F[RecordMetadata],
                                                            publishFailedEvent: Feedback => F[RecordMetadata])(
      event: Event,
      deliveryError: DeliveryError): F[Seq[RecordMetadata]] = {

    val buildFailed   = implicitly[BuildFailed[Event]]
    val buildFeedback = implicitly[BuildFeedback[Event]]

    val legacyFailed = buildFailed.apply(event, deliveryError)
    val feedback     = buildFeedback.apply(event, Some(deliveryError), FeedbackOptions.Failed)

    for {
      record1 <- publishFailedEventLegacy(legacyFailed)
      _ <- {
        Async[F].delay(logInfo(
          (legacyFailed, record1),
          s"Published legacy Failed event: ${legacyFailed.errorCode} - ${legacyFailed.reason} - ${record1.partition}/${record1.offset}"))
      }
      record2 <- publishFailedEvent(feedback)
      _ <- Async[F].delay(logInfo(
        (feedback, record2),
        s"Published feedback event: ${feedback.status} - ${feedback.description} - ${record1.partition}/${record1.offset}"))
    } yield Seq(record1, record2)
  }
}
