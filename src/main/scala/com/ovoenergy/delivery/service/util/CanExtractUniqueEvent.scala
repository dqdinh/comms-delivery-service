package com.ovoenergy.delivery.service.util

import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.comms.model.sms.ComposedSMSV2

trait CanExtractUniqueEvent[Event] {
  def getCommBodyWithMetadata(event: Event): CommBodyWithMetadata
}

object CanExtractUniqueEvent {

  implicit val canExtractComposedEmail = new CanExtractUniqueEvent[ComposedEmailV2] {
    override def getCommBodyWithMetadata(event: ComposedEmailV2) = CommBodyWithMetadata(event.htmlBody, event.metadata)
  }

  implicit val canExtractComposedSms = new CanExtractUniqueEvent[ComposedSMSV2] {
    override def getCommBodyWithMetadata(event: ComposedSMSV2) = CommBodyWithMetadata(event.textBody, event.metadata)
  }

}
