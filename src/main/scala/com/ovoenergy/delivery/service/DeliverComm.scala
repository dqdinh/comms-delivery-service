package com.ovoenergy.delivery.service

import com.ovoenergy.delivery.service.domain.{DeliveryError, DuplicateDeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.persistence.{CanExtractUniqueEvent, DynamoPersistence}
import com.ovoenergy.delivery.service.util.HashFactory
import cats.implicits._
import com.ovoenergy.comms.model.UnexpectedDeliveryError

object DeliverComm {

  def apply[E: CanExtractUniqueEvent](
      hashFactory: HashFactory,
      dynamoPersistence: DynamoPersistence,
      issueComm: E => Either[DeliveryError, GatewayComm]): E => Either[DeliveryError, GatewayComm] = { event =>
    val commRecord = hashFactory.getCommRecord(event)

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
