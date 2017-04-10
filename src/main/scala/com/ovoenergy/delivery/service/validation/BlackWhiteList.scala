package com.ovoenergy.delivery.service.validation

import scala.util.matching.Regex

object BlackWhiteList {

  sealed trait Verdict
  case object OK             extends Verdict
  case object NotWhitelisted extends Verdict
  case object Blacklisted    extends Verdict

  def buildFromRegex(whitelist: Regex, blacklist: Seq[String]): String => Verdict = {
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

  def buildFromLists(whitelist: Seq[String], blacklist: Seq[String]): String => Verdict = {
    val blacklistSet = blacklist.toSet
    val whitelistSet = whitelist.toSet

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
