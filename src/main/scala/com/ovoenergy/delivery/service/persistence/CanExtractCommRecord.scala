package com.ovoenergy.delivery.service.persistence

import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.delivery.service.domain.CommRecord

trait CanExtractCommRecord[Event] {
  def commRecord(event: Event): CommRecord
}

object CanExtractCommRecord {

  implicit val canExtractComposedEmail = new CanExtractCommRecord[ComposedEmailV4] {
    override def commRecord(event: ComposedEmailV4): CommRecord =
      CommRecord(event.hashedComm, event.metadata.createdAt)
  }

  implicit val canExtractComposedSms = new CanExtractCommRecord[ComposedSMSV4] {
    override def commRecord(event: ComposedSMSV4): CommRecord =
      CommRecord(event.hashedComm, event.metadata.createdAt)
  }

  implicit val canExtractComposedPrint = new CanExtractCommRecord[ComposedPrintV2] {
    override def commRecord(event: ComposedPrintV2): CommRecord =
      CommRecord(event.hashedComm, event.metadata.createdAt)
  }

}
