package com.ovoenergy.delivery.service

import com.ovoenergy.delivery.service.domain.{DeliveryError, DuplicateCommError, GatewayComm}
import com.ovoenergy.delivery.service.persistence.DynamoPersistence
import com.ovoenergy.delivery.service.util.{CanExtractUniqueEvent, HashFactory}
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
        Left(DuplicateCommError(commRecord.commHash, UnexpectedDeliveryError))
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
