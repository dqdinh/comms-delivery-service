package com.ovoenergy.delivery.service.kafka.process

import java.time.Clock
import java.util.UUID

import com.ovoenergy.comms.model.ErrorCode.{EmailAddressBlacklisted, EmailGatewayError}
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.email.BlackWhiteList
import com.ovoenergy.delivery.service.email.mailgun._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

object EmailDeliveryProcess extends LoggingWithMDC {

  val errorReasonMappings = Map[EmailDeliveryError, String](
    APIGatewayAuthenticationError -> "Error authenticating with the Email Gateway",
    APIGatewayInternalServerError -> "The Email Gateway had an error",
    APIGatewayBadRequest -> "The Email Gateway did not like our request",
    APIGatewayUnspecifiedError -> "An unexpected response was received from the Email Gateway",
    ExceptionOccurred -> "An error occurred in our service trying to send the email",
    NotWhitelistedEmailAddress -> "The email address was not whitelisted",
    BlacklistedEmailAddress -> "The email address was blacklisted"
  )

  def apply(blackWhiteList: (String) => BlackWhiteList.Verdict,
            emailFailedPublisher: (Failed)  => Future[_],
            emailProgressedPublisher: (EmailProgressed) => Future[_],
            uuidGenerator: () => UUID,
            clock: Clock,
            sendEmail: (ComposedEmail) => Either[EmailDeliveryError, EmailProgressed])(composedEmail: ComposedEmail): Future[_] = {

    val traceToken = composedEmail.metadata.traceToken

    def sendAndProcessComm() = {
      sendEmail(composedEmail) match {
        case Left(failed)      =>
          val failedEvent = buildFailedEvent(failed, EmailGatewayError)
          logDebug(traceToken, s"Issuing failed event $failed")
          emailFailedPublisher(failedEvent)
        case Right(progressed) =>
          logDebug(traceToken, s"Issuing progressed event $progressed")
          emailProgressedPublisher(progressed)
      }
    }

    def buildFailedEvent(emailDeliveryError: EmailDeliveryError, errorCode: ErrorCode) = {
      val metadata = Metadata.fromSourceMetadata("delivery-service", composedEmail.metadata)
      Failed(metadata, composedEmail.internalMetadata, errorReasonMappings.getOrElse(emailDeliveryError, "Unknown error"), errorCode)
    }

    val result = blackWhiteList(composedEmail.recipient) match {
      case BlackWhiteList.OK => 
        sendAndProcessComm()
      case BlackWhiteList.NotWhitelisted =>
        logWarn(traceToken, s"Email addressed is not whitelisted: ${composedEmail.recipient}")
        emailFailedPublisher(buildFailedEvent(NotWhitelistedEmailAddress, EmailAddressBlacklisted))
      case BlackWhiteList.Blacklisted =>
        logWarn(traceToken, s"Email addressed is blacklisted: ${composedEmail.recipient}")
        emailFailedPublisher(buildFailedEvent(BlacklistedEmailAddress, EmailAddressBlacklisted))
    }

    result.recover{
      case NonFatal(err) => logWarn(traceToken, "Skipping event", err)
    }
  }

  override def loggerName: String = "EmailProcesses"
}
