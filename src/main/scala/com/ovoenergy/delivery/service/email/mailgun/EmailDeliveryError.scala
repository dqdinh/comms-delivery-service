package com.ovoenergy.delivery.service.email.mailgun

sealed trait EmailDeliveryError
case object APIGatewayAuthenticationError extends EmailDeliveryError
case object APIGatewayInternalServerError extends EmailDeliveryError
case object APIGatewayBadRequest extends EmailDeliveryError
case object APIGatewayUnspecifiedError extends EmailDeliveryError
case object ExceptionOccurred extends EmailDeliveryError
case object BlacklistedEmailAddress extends EmailDeliveryError

