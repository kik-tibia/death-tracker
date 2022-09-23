package com.kiktibia.deathtracker

import com.typesafe.config.ConfigFactory

object Config {
  private val root = ConfigFactory.load().getConfig("death-tracker")

  val token: String = root.getString("token")
  val guildId: String = root.getString("guild-id")
  val deathsChannelId: String = root.getString("deaths-channel-id")

}
