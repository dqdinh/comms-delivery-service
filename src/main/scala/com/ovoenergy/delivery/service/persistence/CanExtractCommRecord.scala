package com.ovoenergy.delivery.service.persistence

import com.ovoenergy.comms.model.email.ComposedEmailV3
import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.comms.model.sms.ComposedSMSV3
import com.ovoenergy.delivery.service.domain.CommRecord

trait CanExtractCommRecord[Event] {
  def commRecord(event: Event): CommRecord
}

object CanExtractCommRecord {

  implicit val canExtractComposedEmail = new CanExtractCommRecord[ComposedEmailV3] {
    override def commRecord(event: ComposedEmailV3): CommRecord =
      CommRecord(event.hashedComm, event.metadata.createdAt)
  }

  implicit val canExtractComposedSms = new CanExtractCommRecord[ComposedSMSV3] {
    override def commRecord(event: ComposedSMSV3): CommRecord =
      CommRecord(event.hashedComm, event.metadata.createdAt)
  }

  implicit val canExtractComposedPrint = new CanExtractCommRecord[ComposedPrint] {
    override def commRecord(event: ComposedPrint): CommRecord =
      CommRecord(event.hashedComm, event.metadata.createdAt)
  }

}
