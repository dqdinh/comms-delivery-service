package com.ovoenergy.delivery.service.email.mailgun

import com.ovoenergy.comms.ComposedEmail

object BlackListed {
  
  def apply(composedEmail: ComposedEmail, blacklist: Seq[String]) = {
    blacklist.contains(composedEmail.recipient)
  }
  
  

}
