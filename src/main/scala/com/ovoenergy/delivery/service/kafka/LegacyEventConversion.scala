package com.ovoenergy.delivery.service.kafka

import java.time.OffsetDateTime

import com.ovoenergy.comms.model.{Metadata, MetadataV2}
import com.ovoenergy.comms.model.email.{ComposedEmail, ComposedEmailV2}
import com.ovoenergy.comms.model.sms.{ComposedSMS, ComposedSMSV2}

object LegacyEventConversion {

  def toComposedEmailV2(composedEmail: ComposedEmail) = ComposedEmailV2(
    metadata = toMetadataV2(composedEmail.metadata),
    internalMetadata = composedEmail.internalMetadata,
    sender = composedEmail.sender,
    recipient = composedEmail.recipient,
    subject = composedEmail.subject,
    htmlBody = composedEmail.htmlBody,
    textBody = composedEmail.textBody,
    expireAt = composedEmail.expireAt
  )

  def toComposedSMSV2(composedSMS: ComposedSMS) = ComposedSMSV2(
    metadata = toMetadataV2(composedSMS.metadata),
    internalMetadata = composedSMS.internalMetadata,
    recipient = composedSMS.recipient,
    textBody = composedSMS.textBody,
    expireAt = composedSMS.expireAt
  )

  def toMetadataV2(metadata: Metadata): MetadataV2 = MetadataV2(
    createdAt = OffsetDateTime.parse(metadata.createdAt).toInstant.toEpochMilli,
    eventId = metadata.eventId,
    traceToken = metadata.traceToken,
    commManifest = metadata.commManifest,
    friendlyDescription = metadata.friendlyDescription,
    source = metadata.source,
    canary = metadata.canary,
    sourceMetadata = metadata.sourceMetadata.map(toMetadataV2),
    triggerSource = metadata.triggerSource
  )

}
