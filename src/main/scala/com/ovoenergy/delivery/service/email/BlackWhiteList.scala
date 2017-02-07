package com.ovoenergy.delivery.service.email

import com.ovoenergy.comms.model.ComposedEmail
import scala.util.matching.Regex

object BlackWhiteList {

  sealed trait Verdict
  case object OK             extends Verdict
  case object NotWhitelisted extends Verdict
  case object Blacklisted    extends Verdict

  def build(whitelist: Regex, blacklist: Seq[String]): String => Verdict = {
    val blacklistSet = blacklist.toSet

    { recipient =>
      if (matches(whitelist, recipient)) {
        if (blacklistSet.contains(recipient))
          Blacklisted
        else
          OK
      } else NotWhitelisted
    }
  }

  private def matches(regex: Regex, input: String): Boolean =
    regex.pattern.matcher(input).matches

}
