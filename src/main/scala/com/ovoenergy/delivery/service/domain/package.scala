package com.ovoenergy.delivery.service

import java.time.Instant

import com.ovoenergy.comms.model
import com.ovoenergy.comms.model._

package object domain {

  case class GatewayComm(gateway: Gateway, id: String, channel: Channel)
  case class CommRecord(hashedComm: String, createdAt: Instant)

  sealed trait DeliveryError {
    def description: String
    def errorCode: ErrorCode
  }

  case class APIGatewayAuthenticationError(error: ErrorCode) extends DeliveryError {
    val description = "Error authenticating with the Gateway"
    val errorCode   = error
  }
  case class APIGatewayInternalServerError(error: ErrorCode) extends DeliveryError {
    val description = "The Gateway had an error"
    val errorCode   = error
  }
  case class APIGatewayBadRequest(error: ErrorCode) extends DeliveryError {
    val description = "The Gateway did not like our request"
    val errorCode   = error
  }
  case class APIGatewayUnspecifiedError(error: ErrorCode) extends DeliveryError {
    val description = "An unexpected response was received from the Gateway"
    val errorCode   = error
  }
  case class ExceptionOccurred(error: ErrorCode) extends DeliveryError {
    val description = "An error occurred in our service trying to send the comm"
    val errorCode   = error
  }
  case class EmailAddressBlacklisted(emailAddress: String) extends DeliveryError {
    val description = s"Email addressed was blacklisted: $emailAddress"
    val errorCode   = model.EmailAddressBlacklisted
  }
  case class EmailAddressNotWhitelisted(emailAddress: String) extends DeliveryError {
    val description = s"Email addressed was not whitelisted: $emailAddress"
    val errorCode   = model.EmailAddressBlacklisted
  }

  case object Expired extends DeliveryError {
    val description = "Not delivering because the comm has expired"
    val errorCode   = model.CommExpired
  }

  case class DynamoError(error: ErrorCode) extends DeliveryError {
    val description = "An error occurred while trying to connect to DynamoDB"
    val errorCode   = error
  }

  case class DuplicateDeliveryError(commHash: String) extends DeliveryError {
    val description = s"CommHash $commHash has already been delivered!"
    val errorCode   = DuplicateCommError
  }

  case class S3ConnectionError(error: ErrorCode, bucketName: String) extends DeliveryError {
    val description = s"An error occurred while trying to connect to S3 bucket $bucketName."
    val errorCode   = error
  }

  case class AmazonS3Error(error: ErrorCode, bucketName: String, key: String) extends DeliveryError {
    val description = s"Key $key does not exist in bucket $bucketName."
    val errorCode   = error
  }

  case class StannpError(error: ErrorCode, message: String) extends DeliveryError {
    val description = message
    val errorCode   = error
  }

  case class StannpConnectionError(error: ErrorCode) extends DeliveryError {
    val description = s"Failed to connect to Stannp."
    val errorCode   = error
  }

  case object TemplateDetailsNotFoundError extends DeliveryError {
    val description = s"Failed to retrieve template details"
    val errorCode   = model.UnexpectedDeliveryError
  }
}
