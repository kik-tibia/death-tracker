package com.kiktibia.deathtracker

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild

import scala.concurrent.ExecutionContextExecutor

object BotApp extends App with StrictLogging {
  logger.info("Starting up")

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher

  private val jda = JDABuilder.createDefault(Config.token)
    .build()

  jda.awaitReady()
  logger.info("JDA ready")

  private val guild: Guild = jda.getGuildById(Config.guildId)

  private val deathsChannel = guild.getTextChannelById(Config.deathsChannelId)
  private val deathTrackerStream = new DeathTrackerStream(deathsChannel)

  deathTrackerStream.stream.run()
}


