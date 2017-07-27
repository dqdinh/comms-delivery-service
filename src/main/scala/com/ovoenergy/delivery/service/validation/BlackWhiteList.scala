package com.ovoenergy.delivery.service.validation

import com.ovoenergy.delivery.config.{EmailAppConfig, SmsAppConfig}

import scala.util.matching.Regex

object BlackWhiteList {

  sealed trait Verdict
  case object OK             extends Verdict
  case object NotWhitelisted extends Verdict
  case object Blacklisted    extends Verdict

  def buildForEmail(implicit emailConf: EmailAppConfig): String => Verdict = {
    val blacklistSet = emailConf.blacklist.toSet

    { recipient =>
      if (matches(emailConf.whitelist.r, recipient)) {
        if (blacklistSet.contains(recipient))
          Blacklisted
        else
          OK
      } else NotWhitelisted
    }
  }

  def buildForSms(implicit smsconf: SmsAppConfig): String => Verdict = {
    val blacklistSet = smsconf.blacklist.toSet
    val whitelistSet = smsconf.whitelist.toSet

    { recipient =>
      if (whitelistSet.contains(recipient) || whitelistSet.isEmpty) {
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
