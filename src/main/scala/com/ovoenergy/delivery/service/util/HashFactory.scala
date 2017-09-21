package com.ovoenergy.delivery.service.util

import java.time.Instant
import java.security.MessageDigest

import com.ovoenergy.comms.model.{CommManifest, DeliverTo, MetadataV2}
import com.ovoenergy.delivery.service.util.CanExtractUniqueEvent._

case class CommRecord(commHash: String, createdAt: Instant)
case class UniqueEvent(deliverTo: DeliverTo, commBody: String, commManifest: CommManifest)
case class CommBodyWithMetadata(commBody: String, metadata: MetadataV2)

class HashFactory {

  val md = MessageDigest.getInstance("MD5")

  def getHashedCommBody[Event](event: Event)(implicit canExtractCommRecord: CanExtractUniqueEvent[Event]): CommRecord = {

    val commBodyWithMetadata = canExtractCommRecord.getCommBodyWithMetadata(event)
    val uniqueEvent = UniqueEvent(commBodyWithMetadata.metadata.deliverTo,
                                  commBodyWithMetadata.commBody,
                                  commBodyWithMetadata.metadata.commManifest)
    val commHash = md.digest(uniqueEvent.toString.getBytes)

    CommRecord(new String(commHash), commBodyWithMetadata.metadata.createdAt)
  }
}
