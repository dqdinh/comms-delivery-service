package com.ovoenergy.delivery.service.validation

import java.time.{Clock, Instant}

object ExpiryCheck {

  def isExpired(clock: Clock)(expireAt: Option[Instant]): Boolean =
    expireAt.exists(_.isBefore(Instant.now(clock)))

}
