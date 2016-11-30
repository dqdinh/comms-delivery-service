package com.ovoenergy.delivery.service.email

import com.ovoenergy.comms.model.ComposedEmail

object BlackListed {
  
  def apply(blacklist: Seq[String])(composedEmail: ComposedEmail): Boolean = {
    blacklist.contains(composedEmail.recipient)
  }
  
  

}
