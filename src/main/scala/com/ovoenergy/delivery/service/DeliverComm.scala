package com.ovoenergy.delivery.service

import cats.effect.Async
import com.ovoenergy.delivery.service.domain.{DeliveryError, DuplicateDeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.persistence.{CanExtractCommRecord, DynamoPersistence}
import cats.implicits._

object DeliverComm {

  def apply[F[_]: Async, E](dynamoPersistence: DynamoPersistence, issueComm: E => Either[DeliveryError, GatewayComm])(
      implicit canExtractCommRecord: CanExtractCommRecord[E]): E => F[Either[DeliveryError, GatewayComm]] = {
    event: E =>
      val commRecord = canExtractCommRecord.commRecord(event)

      def issueCommIfUnique(isDuplicate: Boolean): Either[DeliveryError, GatewayComm] = {
        if (isDuplicate)
          Left(DuplicateDeliveryError(commRecord.hashedComm))
        else
          issueComm(event)
      }

      for {
        isDuplicate <- dynamoPersistence.exists(commRecord)
        gatewayComm <- Async[F].pure(isDuplicate.flatMap(issueCommIfUnique))
        _           <- dynamoPersistence.persistHashedComm(commRecord)
      } yield gatewayComm
  }
}
