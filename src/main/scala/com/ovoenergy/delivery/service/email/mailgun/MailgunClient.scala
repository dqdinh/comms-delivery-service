package com.ovoenergy.delivery.service.email.mailgun

import com.ovoenergy.comms.{ComposedEmail, Failed}

trait MailgunClient {

  def sendMail: ComposedEmail => Either[Failed, EmailProgressed] = ???






}
