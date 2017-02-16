package com.ovoenergy.delivery.service.validation

import java.time.{Clock, OffsetDateTime}

import scala.util.Try

object ExpiryCheck {

  def isExpired(clock: Clock)(expireAt: Option[String]): Boolean = {
    val expiry: Option[OffsetDateTime] = expireAt.flatMap(x => Try(OffsetDateTime.parse(x)).toOption)
    expiry.exists(_.isBefore(OffsetDateTime.now(clock)))
  }

}
