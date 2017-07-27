package com.ovoenergy.delivery.service.validation

import java.time.{Clock, Instant}

object ExpiryCheck {

  def isExpired(implicit clock: Clock) =
    (expireAt: Option[Instant]) => expireAt.exists(_.isBefore(Instant.now(clock)))
}
