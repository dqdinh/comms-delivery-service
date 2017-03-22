package com.ovoenergy.delivery.service

import com.ovoenergy.comms.model._

package object domain {

  case class GatewayComm(gateway: String, id: String, channel: Channel)

  sealed trait DeliveryError {
    def description: String
    def errorCode: ErrorCode
  }

  case object APIGatewayAuthenticationError extends DeliveryError {
    val description = "Error authenticating with the Email Gateway"
    val errorCode = ErrorCode.EmailGatewayError
  }
  case object APIGatewayInternalServerError extends DeliveryError {
    val description = "The Email Gateway had an error"
    val errorCode = ErrorCode.EmailGatewayError
  }
  case object APIGatewayBadRequest extends DeliveryError {
    val description = "The Email Gateway did not like our request"
    val errorCode = ErrorCode.EmailGatewayError
  }
  case object APIGatewayUnspecifiedError extends DeliveryError {
    val description = "An unexpected response was received from the Email Gateway"
    val errorCode = ErrorCode.EmailGatewayError
  }
  case object ExceptionOccurred extends DeliveryError {
    val description = "An error occurred in our service trying to send the email"
    val errorCode = ErrorCode.EmailGatewayError
  }
  case class EmailAddressBlacklisted(emailAddress: String) extends DeliveryError {
    val description =  s"Email addressed was blacklisted: $emailAddress"
    val errorCode = ErrorCode.EmailAddressBlacklisted
  }
  case class EmailAddressNotWhitelisted(emailAddress: String) extends DeliveryError {
    val description =  s"Email addressed was not whitelisted: $emailAddress"
    val errorCode = ErrorCode.EmailAddressBlacklisted
  }
  case object Expired extends DeliveryError {
    val description = "Not delivering because the comm has expired"
    val errorCode = ErrorCode.CommExpired
  }

}
