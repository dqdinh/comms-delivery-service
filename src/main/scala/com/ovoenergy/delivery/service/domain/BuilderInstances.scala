package com.ovoenergy.delivery.service.domain

import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.kafka.common.event.EventMetadata

trait BuildFailed[T] {
  def apply(t: T, deliveryError: DeliveryError): FailedV3
}

object BuildFailed {
  def instance[T](f: (T, DeliveryError) => FailedV3) = {
    new BuildFailed[T] {
      override def apply(t: T, deliveryError: DeliveryError): FailedV3 = f(t, deliveryError)
    }
  }
}

trait BuildFeedback[T] {
  def apply(t: T, deliveryError: Option[DeliveryError], feedbackStatus: FeedbackStatus): Feedback
}

object BuildFeedback {
  def instance[T](f: (T, Option[DeliveryError], FeedbackStatus) => Feedback) = {
    new BuildFeedback[T] {
      override def apply(t: T, deliveryError: Option[DeliveryError], feedbackStatus: FeedbackStatus): Feedback =
        f(t, deliveryError, feedbackStatus)
    }
  }
}

trait BuilderInstances {

  def extractCustomer(deliverTo: DeliverTo): Option[Customer] = {
    deliverTo match {
      case customer: Customer => Some(customer)
      case _                  => None
    }
  }

  implicit val buildfeedbackFromEmail = {
    BuildFeedback.instance[ComposedEmailV4] { (composedEvent, deliveryError, feedbackStatus) =>
      Feedback(
        composedEvent.metadata.commId,
        Some(composedEvent.metadata.friendlyDescription),
        extractCustomer(composedEvent.metadata.deliverTo),
        feedbackStatus,
        deliveryError.map(_.description),
        None,
        Some(Email),
        Some(composedEvent.metadata.templateManifest),
        EventMetadata.fromMetadata(composedEvent.metadata, s"${composedEvent.metadata.eventId}-feedback")
      )
    }
  }

  implicit val buildfeedbackFromSms = {
    BuildFeedback.instance[ComposedSMSV4] { (composedEvent, deliveryError, feedbackStatus) =>
      Feedback(
        composedEvent.metadata.commId,
        Some(composedEvent.metadata.friendlyDescription),
        extractCustomer(composedEvent.metadata.deliverTo),
        feedbackStatus,
        deliveryError.map(_.description),
        None,
        Some(SMS),
        Some(composedEvent.metadata.templateManifest),
        EventMetadata.fromMetadata(composedEvent.metadata, s"${composedEvent.metadata.eventId}-feedback")
      )
    }
  }

  implicit val buildfeedbackFromPrint = {
    BuildFeedback.instance[ComposedPrintV2] { (composedEvent, deliveryError, feedbackStatus) =>
      Feedback(
        composedEvent.metadata.commId,
        Some(composedEvent.metadata.friendlyDescription),
        extractCustomer(composedEvent.metadata.deliverTo),
        feedbackStatus,
        deliveryError.map(_.description),
        None,
        Some(Print),
        Some(composedEvent.metadata.templateManifest),
        EventMetadata.fromMetadata(composedEvent.metadata, s"${composedEvent.metadata.eventId}-feedback")
      )
    }
  }

  implicit val buildFailedFromEmail = {
    BuildFailed.instance[ComposedEmailV4] { (composedEvent, deliveryError) =>
      FailedV3(
        metadata = MetadataV3.fromSourceMetadata("delivery-service",
                                                 composedEvent.metadata,
                                                 s"${composedEvent.metadata.eventId}-failed"),
        internalMetadata = composedEvent.internalMetadata,
        reason = deliveryError.description,
        errorCode = deliveryError.errorCode
      )
    }
  }

  implicit val buildfailedFromSms = {
    BuildFailed.instance[ComposedSMSV4] { (composedEvent, deliveryError) =>
      FailedV3(
        metadata = MetadataV3.fromSourceMetadata("delivery-service",
                                                 composedEvent.metadata,
                                                 s"${composedEvent.metadata.eventId}-failed"),
        internalMetadata = composedEvent.internalMetadata,
        reason = deliveryError.description,
        errorCode = deliveryError.errorCode
      )
    }
  }

  implicit val buildfailedFromPrint = {
    BuildFailed.instance[ComposedPrintV2] { (composedEvent, deliveryError) =>
      FailedV3(
        metadata = MetadataV3.fromSourceMetadata("delivery-service",
                                                 composedEvent.metadata,
                                                 s"${composedEvent.metadata.eventId}-failed"),
        internalMetadata = composedEvent.internalMetadata,
        reason = deliveryError.description,
        errorCode = deliveryError.errorCode
      )
    }
  }
}

object builders extends BuilderInstances
