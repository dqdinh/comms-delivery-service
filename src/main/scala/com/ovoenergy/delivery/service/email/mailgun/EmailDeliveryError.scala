package com.ovoenergy.delivery.service.email.mailgun

sealed trait EmailDeliveryError
case object UnexpectedError