package com.ovoenergy.delivery.service.kafka.process

import java.util.UUID

import com.ovoenergy.comms.model.ErrorCode.{CommExpired, EmailAddressBlacklisted, EmailGatewayError}
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.email.mailgun._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.validation.BlackWhiteList

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

object EmailDeliveryProcess extends LoggingWithMDC {

  // LHS represents sending a Failed event
  private type Result[A] = Either[Future[_], Future[A]]

  private val Proceed: Result[Unit] = Right(Future.successful(()))

  def apply(checkBlackWhiteList: (String) => BlackWhiteList.Verdict,
            isExpired: Option[String] => Boolean,
            sendFailedEvent: (Failed) => Future[_],
            sendEmailProgressedEvent: (EmailProgressed) => Future[_],
            generateUUID: () => UUID,
            sendEmail: (ComposedEmail) => Either[EmailDeliveryError, EmailProgressed])(
      composedEmail: ComposedEmail): Future[_] = {

    val traceToken = composedEmail.metadata.traceToken

    def deliveryErrorToFailedEvent(emailDeliveryError: EmailDeliveryError, errorCode: ErrorCode) =
      buildFailedEvent(emailDeliveryError.description, errorCode)

    def buildFailedEvent(reason: String, errorCode: ErrorCode) = {
      val metadata = Metadata.fromSourceMetadata("delivery-service", composedEmail.metadata)
      Failed(metadata, composedEmail.internalMetadata, reason, errorCode)
    }

    def blackWhiteListCheck: Result[Unit] = checkBlackWhiteList(composedEmail.recipient) match {
      case BlackWhiteList.OK =>
        Proceed
      case BlackWhiteList.NotWhitelisted =>
        logWarn(traceToken, s"Email addressed is not whitelisted: ${composedEmail.recipient}")
        Left(sendFailedEvent(buildFailedEvent("The email address was not whitelisted", EmailAddressBlacklisted)))
      case BlackWhiteList.Blacklisted =>
        logWarn(traceToken, s"Email addressed is blacklisted: ${composedEmail.recipient}")
        Left(sendFailedEvent(buildFailedEvent("The email address was blacklisted", EmailAddressBlacklisted)))
    }

    def expiryCheck: Result[Unit] = {
      if (isExpired(composedEmail.expireAt))
        Left(sendFailedEvent(buildFailedEvent("Not delivering because the comm has expired", CommExpired)))
      else
        Proceed
    }

    def sendAndProcessComm: Result[_] = {
      sendEmail(composedEmail) match {
        case Left(failed) =>
          val failedEvent = deliveryErrorToFailedEvent(failed, EmailGatewayError)
          logDebug(traceToken, s"Issuing failed event $failed")
          Left(sendFailedEvent(failedEvent))
        case Right(progressed) =>
          logDebug(traceToken, s"Issuing progressed event $progressed")
          Right(sendEmailProgressedEvent(progressed))
      }
    }

    import cats.syntax.either._
    val future: Result[_] =
      for {
        _      <- blackWhiteListCheck
        _      <- expiryCheck
        result <- sendAndProcessComm
      } yield result

    future.merge.recover {
      case NonFatal(err) => logWarn(traceToken, "Skipping event", err)
    }
  }

}
