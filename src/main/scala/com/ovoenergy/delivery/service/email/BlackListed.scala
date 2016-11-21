package com.ovoenergy.delivery.service.email

import com.ovoenergy.comms.ComposedEmail

object BlackListed {
  
  def apply(blacklist: Seq[String])(composedEmail: ComposedEmail) = {
    blacklist.contains(composedEmail.recipient)
  }
  
  

}
