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
  def apply(t: T, deliveryError: DeliveryError): Feedback
}

object BuildFeedback {
  def instance[T](f: (T, DeliveryError) => Feedback) = {
    new BuildFeedback[T] {
      override def apply(t: T, deliveryError: DeliveryError): Feedback = f(t, deliveryError)
    }
  }
}

trait BuilderInstances {
  /*
    TODO: Create deterministic function to generate a new eventId from the previous eventId

    The event id for the new event (Failed or Feedback) should be different to the previous,
    but deterministic so if duplicates are created accidentally these can be filtered
   */

  private def extractCustomer(deliverTo: DeliverTo): Option[Customer] = {
    deliverTo match {
      case customer: Customer => Some(customer)
      case _                  => None
    }
  }

  implicit val buildfeedbackFromEmail = {
    BuildFeedback.instance[ComposedEmailV4] { (composedEvent, deliveryError) =>
      Feedback(
        composedEvent.metadata.commId,
        extractCustomer(composedEvent.metadata.deliverTo),
        FeedbackOptions.Failed,
        Some(deliveryError.description),
        None,
        Some(Email),
        EventMetadata.fromMetadata(composedEvent.metadata, ???)
      )
    }
  }

  implicit val buildfeedbackFromSms = {
    BuildFeedback.instance[ComposedSMSV4] { (composedEvent, deliveryError) =>
      Feedback(
        composedEvent.metadata.commId,
        extractCustomer(composedEvent.metadata.deliverTo),
        FeedbackOptions.Failed,
        Some(deliveryError.description),
        None,
        Some(SMS),
        EventMetadata.fromMetadata(composedEvent.metadata, ???)
      )
    }
  }

  implicit val buildfeedbackFromPrint = {
    BuildFeedback.instance[ComposedPrintV2] { (composedEvent, deliveryError) =>
      Feedback(
        composedEvent.metadata.commId,
        extractCustomer(composedEvent.metadata.deliverTo),
        FeedbackOptions.Failed,
        Some(deliveryError.description),
        None,
        Some(Print),
        EventMetadata.fromMetadata(composedEvent.metadata, ???)
      )
    }
  }

  implicit val buildFailedFromEmail = {
    BuildFailed.instance[ComposedEmailV4] { (composedEvent, deliveryError) =>
      FailedV3(
        metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
        internalMetadata = composedEvent.internalMetadata,
        reason = deliveryError.description,
        errorCode = deliveryError.errorCode
      )
    }
  }

  implicit val buildfailedFromSms = {
    BuildFailed.instance[ComposedSMSV4] { (composedEvent, deliveryError) =>
      FailedV3(
        metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
        internalMetadata = composedEvent.internalMetadata,
        reason = deliveryError.description,
        errorCode = deliveryError.errorCode
      )
    }
  }

  implicit val buildfailedFromPrint = {
    BuildFailed.instance[ComposedPrintV2] { (composedEvent, deliveryError) =>
      FailedV3(
        metadata = MetadataV3.fromSourceMetadata("delivery-service", composedEvent.metadata),
        internalMetadata = composedEvent.internalMetadata,
        reason = deliveryError.description,
        errorCode = deliveryError.errorCode
      )
    }
  }
}

object BuilderInstances extends BuilderInstances
