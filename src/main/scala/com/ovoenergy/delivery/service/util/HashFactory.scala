package com.ovoenergy.delivery.service.util

import java.time.Instant
import java.security.MessageDigest

import com.ovoenergy.comms.model.{CommManifest, DeliverTo, MetadataV2}
import com.ovoenergy.delivery.service.persistence.CanExtractUniqueEvent

case class CommRecord(hashedComm: String, createdAt: Instant)
case class CommRecordWithMetadata(commBody: String, metadataV2: MetadataV2) {
  def toHashableComm = HashableComm(metadataV2.deliverTo, commBody, metadataV2.commManifest)
}
case class HashableComm(deliverTo: DeliverTo, commBody: String, commManifest: CommManifest)

class HashFactory {

  def getCommRecord[Event](event: Event)(implicit canExtractCommRecord: CanExtractUniqueEvent[Event]): CommRecord = {

    val commRecordWithMetadata = canExtractCommRecord.getCommBodyWithMetadata(event)

    val commHash = MessageDigest.getInstance("MD5").digest(commRecordWithMetadata.toHashableComm.toString.getBytes)

    CommRecord(new String(commHash), commRecordWithMetadata.metadataV2.createdAt)
  }
}
