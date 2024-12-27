package com.kiktibia.deathtracker

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._

object BotApp extends App with StrictLogging {
  logger.info("Starting up")

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher

  private val jda = JDABuilder.createDefault(Config.token).build()

  jda.awaitReady()
  logger.info("JDA ready")

  private val guilds: List[Guild] = jda.getGuilds().asScala.toList
  logger.info(guilds.toString())

  private val deathTrackerStream = new DeathTrackerStream(guilds)
  deathTrackerStream.stream.run()

  Await.result(actorSystem.whenTerminated, Duration.Inf)
}
