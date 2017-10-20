package com.ovoenergy.delivery.service.print

import java.time.Instant

import com.ovoenergy.comms.model.print.ComposedPrint
import com.ovoenergy.delivery.service.domain.{DeliveryError, Expired, GatewayComm}
import com.ovoenergy.delivery.service.persistence.AwsProvider.S3Context
import com.ovoenergy.delivery.service.persistence.S3PdfRepo
import com.ovoenergy.delivery.service.sms.IssueSMS.logInfo

object IssuePrint {

  type PdfDocument = Array[Byte]

  def issue(isExpired: Option[Instant] => Boolean,
            getPdf: ComposedPrint => Either[DeliveryError, PdfDocument],
            sendPrint: (PdfDocument, ComposedPrint) => Either[DeliveryError, GatewayComm])(
      composedPrint: ComposedPrint): Either[DeliveryError, GatewayComm] = {

    def expiryCheck: Either[DeliveryError, Unit] = {
      if (isExpired(composedPrint.expireAt)) {
        logInfo(composedPrint, s"Comm was expired")
        Left(Expired)
      } else {
        Right(())
      }
    }

    import cats.syntax.either._
    for {
      _           <- expiryCheck
      pdf         <- getPdf(composedPrint)
      gatewayComm <- sendPrint(pdf, composedPrint)
    } yield gatewayComm
  }
}
