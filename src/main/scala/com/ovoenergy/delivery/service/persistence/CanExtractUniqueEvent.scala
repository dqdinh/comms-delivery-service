package com.ovoenergy.delivery.service.persistence

import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.comms.model.sms.ComposedSMSV2
import com.ovoenergy.delivery.service.util.CommRecordWithMetadata

trait CanExtractUniqueEvent[Event] {
  def getCommBodyWithMetadata(event: Event): CommRecordWithMetadata
}

object CanExtractUniqueEvent {

  implicit val canExtractComposedEmail = new CanExtractUniqueEvent[ComposedEmailV2] {
    override def getCommBodyWithMetadata(event: ComposedEmailV2) =
      CommRecordWithMetadata(
        event.htmlBody,
        event.metadata
      )
  }

  implicit val canExtractComposedSms = new CanExtractUniqueEvent[ComposedSMSV2] {
    override def getCommBodyWithMetadata(event: ComposedSMSV2) =
      CommRecordWithMetadata(
        event.textBody,
        event.metadata
      )
  }

}
