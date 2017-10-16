package com.ovoenergy.delivery.service

import java.time.Instant

import com.ovoenergy.delivery.service.domain.{CommRecord, DeliveryError, DuplicateDeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.persistence.{DynamoPersistence, CanExtractCommRecord}
import cats.implicits._
import com.ovoenergy.comms.model.UnexpectedDeliveryError

object DeliverComm {

  def apply[E](dynamoPersistence: DynamoPersistence, issueComm: E => Either[DeliveryError, GatewayComm])(
      implicit canExtractCommRecord: CanExtractCommRecord[E]): E => Either[DeliveryError, GatewayComm] = { event =>
    val commRecord = canExtractCommRecord.commRecord(event)

    def issueCommIfUnique(isDuplicate: Boolean): Either[DeliveryError, GatewayComm] = {
      if (isDuplicate)
        Left(DuplicateDeliveryError(commRecord.hashedComm))
      else
        issueComm(event)
    }

    for {
      isDuplicate <- dynamoPersistence.exists(commRecord)
      gatewayComm <- issueCommIfUnique(isDuplicate)
      _           <- dynamoPersistence.persistHashedComm(commRecord)
    } yield gatewayComm
  }

}
