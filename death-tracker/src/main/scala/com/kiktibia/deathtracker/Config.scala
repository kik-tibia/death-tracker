package com.kiktibia.deathtracker

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._

object Config {
  private val root = ConfigFactory.load().getConfig("death-tracker")

  val token: String = root.getString("token")
  val guildId: String = root.getString("guild-id")
  val deathsChannelId: String = root.getString("deaths-channel-id")
  val creatureUrlMappings: Map[String, String] = root.getObject("creature-url-mappings").asScala.map {
    case (k, v) => k -> v.unwrapped().toString
  }.toMap
  val notableCreatures: List[String] = root.getStringList("notable-creatures").asScala.toList

}
