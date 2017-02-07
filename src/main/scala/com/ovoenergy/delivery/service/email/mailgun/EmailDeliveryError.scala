package com.ovoenergy.delivery.service.email.mailgun

sealed trait EmailDeliveryError { def description: String }

case object APIGatewayAuthenticationError extends EmailDeliveryError {
  val description = "Error authenticating with the Email Gateway"
}
case object APIGatewayInternalServerError extends EmailDeliveryError {
  val description = "The Email Gateway had an error"
}
case object APIGatewayBadRequest extends EmailDeliveryError {
  val description = "The Email Gateway did not like our request"
}
case object APIGatewayUnspecifiedError extends EmailDeliveryError {
  val description = "An unexpected response was received from the Email Gateway"
}
case object ExceptionOccurred extends EmailDeliveryError {
  val description = "An error occurred in our service trying to send the email"
}
