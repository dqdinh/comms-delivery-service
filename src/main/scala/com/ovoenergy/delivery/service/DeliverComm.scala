package com.ovoenergy.delivery.service

import com.ovoenergy.delivery.service.domain.{DeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.persistence.DynamoPersistence
import com.ovoenergy.delivery.service.util.{CanExtractUniqueEvent, HashFactory}
import cats.implicits._

object DeliverComm {

  def apply[E: CanExtractUniqueEvent](
      hashFactory: HashFactory,
      dynamoPersistence: DynamoPersistence,
      issueComm: E => Either[DeliveryError, GatewayComm]): E => Either[DeliveryError, GatewayComm] = { event =>
    val commRecord = hashFactory.getHashedCommBody(event)

    for {
      commRecord  <- dynamoPersistence.retrieveHashedComm(commRecord)
      gatewayComm <- issueComm(event)
      _           <- dynamoPersistence.persistHashedComm(commRecord)
    } yield (gatewayComm)

  }
}
