package com.ovoenergy.delivery.service.email.mailgun

import com.ovoenergy.comms.Metadata

sealed trait EmailStatus
case object Queued extends EmailStatus

case class EmailProgressed(metadata: Metadata, status: EmailStatus, gatewayId: String)

