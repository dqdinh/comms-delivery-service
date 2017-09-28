package com.ovoenergy.delivery.service.util

import java.time.Instant
import java.security.MessageDigest

import com.ovoenergy.comms.model.{CommManifest, DeliverTo, MetadataV2}
import com.ovoenergy.delivery.service.util.CanExtractUniqueEvent._

case class CommRecord(commHash: String, createdAt: Instant)
case class UniqueEvent(deliverTo: DeliverTo, commBody: String, commManifest: CommManifest, createdAt: Instant)

class HashFactory {

  def getCommRecord[Event](event: Event)(implicit canExtractCommRecord: CanExtractUniqueEvent[Event]): CommRecord = {

    val uniqueEvent = canExtractCommRecord.getCommBodyWithMetadata(event)

    val commHash = MessageDigest.getInstance("MD5").digest(uniqueEvent.toString.getBytes)

    CommRecord(new String(commHash), uniqueEvent.createdAt)
  }
}
