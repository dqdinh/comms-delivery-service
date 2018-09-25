package com.ovoenergy.delivery.service

import cats.effect.Async
import com.ovoenergy.delivery.service.domain.{DeliveryError, DuplicateDeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.persistence.{CanExtractCommRecord, DynamoPersistence}
import cats.implicits._

object DeliverComm {

  def apply[F[_], E](dynamoPersistence: DynamoPersistence[F], issueComm: E => F[GatewayComm])(
      implicit canExtractCommRecord: CanExtractCommRecord[E],
      F: Async[F]): E => F[GatewayComm] = { event: E =>
    val commRecord = canExtractCommRecord.commRecord(event)

    def issueCommIfUnique(isDuplicate: Boolean): F[GatewayComm] = {
      if (isDuplicate)
        F.raiseError(DuplicateDeliveryError(commRecord.hashedComm))
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
