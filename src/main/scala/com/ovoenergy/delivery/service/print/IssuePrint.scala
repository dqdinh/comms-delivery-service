package com.ovoenergy.delivery.service.print

import java.time.Instant

import cats.effect.Sync
import cats.implicits._
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.delivery.service.domain.{Content, Expired, GatewayComm}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.persistence.{CommContent}

object IssuePrint extends LoggingWithMDC {
  def issue[F[_]](isExpired: Option[Instant] => Boolean,
                  content: CommContent[F],
                  sendPrint: (Content.Print, ComposedPrintV2) => F[GatewayComm])(
      implicit F: Sync[F]): ComposedPrintV2 => F[GatewayComm] = { composedPrint: ComposedPrintV2 =>
    def expiryCheck: F[Unit] = {
      if (isExpired(composedPrint.expireAt))
        F.delay(logInfo(composedPrint, s"Comm was expired")) >> F.raiseError(Expired)
      else
        F.unit
    }

    for {
      _            <- expiryCheck
      printContent <- content.getPrintContent(composedPrint)
      gatewayComm  <- sendPrint(printContent, composedPrint)
    } yield gatewayComm
  }
}
