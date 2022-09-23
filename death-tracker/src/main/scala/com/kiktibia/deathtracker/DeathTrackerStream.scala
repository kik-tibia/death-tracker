package com.kiktibia.deathtracker

import akka.actor.Cancellable
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}
import akka.stream.{Attributes, Materializer, Supervision}
import com.kiktibia.deathtracker.tibiadata.TibiaDataClient
import com.kiktibia.deathtracker.tibiadata.response.{CharacterResponse, Deaths, WorldResponse}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.TextChannel

import java.time.ZonedDateTime
import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._

class DeathTrackerStream(deathsChannel: TextChannel)(implicit ex: ExecutionContextExecutor, mat: Materializer) extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  case class CharKey(char: String, time: ZonedDateTime)

  case class CharDeath(char: CharacterResponse, death: Deaths)

  private val recentDeaths = mutable.Set.empty[CharKey]
  private val recentOnline = mutable.Set.empty[CharKey]

  private val tibiaDataClient = new TibiaDataClient()

  private val deathRecentDuration = 30 * 60 // 30 minutes for a death to count as recent enough to be worth notifying
  private val onlineRecentDuration = 10 * 60 // 10 minutes for a character to still be checked for deaths after logging off

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the DeathTrackerStream:", e)
    Supervision.Resume
  }
  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)

  private lazy val sourceTick = Source.tick(2.seconds, 180.seconds, ())

  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info("Running stream")
    tibiaDataClient.getWorld() // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[WorldResponse].mapAsync(1) { worldResponse =>
    val now = ZonedDateTime.now()
    val online: List[String] = worldResponse.worlds.world.online_players.map(_.name)
    recentOnline.filterInPlace(i => !online.contains(i.char)) // Remove existing online chars from the list...
    recentOnline.addAll(online.map(i => CharKey(i, now))) // ...and add them again, with an updated online time
    val charsToCheck: Set[String] = recentOnline.map(_.char).toSet
    Source(charsToCheck).mapAsyncUnordered(16)(tibiaDataClient.getCharacter).runWith(Sink.collection).map(_.toSet)
  }.withAttributes(logAndResume)

  private lazy val scanForDeaths = Flow[Set[CharacterResponse]].mapAsync(1) { characterResponses =>
    val now = ZonedDateTime.now()
    val newDeaths = characterResponses.flatMap { char =>
      val deaths: List[Deaths] = char.characters.deaths.getOrElse(List.empty)
      deaths.flatMap { death =>
        val deathTime = ZonedDateTime.parse(death.time)
        val deathAge = java.time.Duration.between(deathTime, now).getSeconds
        val charDeath = CharKey(char.characters.character.name, deathTime)
        if (deathAge < deathRecentDuration && !recentDeaths.contains(charDeath)) {
          recentDeaths.add(charDeath)
          Some(CharDeath(char, death))
        }
        else None
      }
    }
    Future.successful(newDeaths)
  }.withAttributes(logAndResume)

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharDeath]].mapAsync(1) { charDeaths =>
    val embeds = charDeaths.map { charDeath =>
      val charName = charDeath.char.characters.character.name
      val killer = charDeath.death.killers.last.name
      val epochSecond = ZonedDateTime.parse(charDeath.death.time).toEpochSecond
      new EmbedBuilder()
        .setTitle(charName, charUrl(charName))
        .setDescription(s"Killed at level ${charDeath.death.level.toInt} by **$killer**\nKilled at <t:$epochSecond>")
        .setThumbnail(creatureImageUrl(killer))
        .setColor(13773097)
        .build()
    }
    if (embeds.nonEmpty) {
      deathsChannel.sendMessageEmbeds(embeds.asJava).queue()
    }
    cleanUp()

    Future.successful()
  }.withAttributes(logAndResume)

  // Remove players from the list who haven't logged in for a while. Remove old saved deaths.
  private def cleanUp(): Unit = {
    val now = ZonedDateTime.now()
    recentOnline.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < onlineRecentDuration
    }
    recentDeaths.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < deathRecentDuration
    }
  }

  private def charUrl(char: String): String =
    s"https://www.tibia.com/community/?name=${char.replaceAll(" ", "+")}"

  private def creatureImageUrl(creature: String): String = {
    val creatureExceptions = HashMap(
      "sabretooth" -> "Sabretooth_(Creature)",
      "a trap" -> "Dead_Human1",
      "trap" -> "Dead_Human1",
      "Avalanche" -> "Avalanche_(Creature)",
      "Yalahari" -> "Yalahari_(Creature)",
      "lava puddle" -> "Lava_Puddle_(Creature)"
    )

    val finalCreature = creatureExceptions.getOrElse(creature, {
      val rx = """( A| Of| The| In| On| To| And| With| From)(?=( ))""".r
      val parsed = creature.split(' ').map(_.capitalize).mkString(" ")
        .replaceAll(" ", "_")
      rx.replaceAllIn(parsed, m => m.group(1).toLowerCase)
    })
    s"https://tibia.fandom.com/wiki/Special:Redirect/file/$finalCreature.gif"
  }

  lazy val stream: RunnableGraph[Cancellable] =
    sourceTick via
      getWorld via
      getCharacterData via
      scanForDeaths via
      postToDiscordAndCleanUp to Sink.ignore

}
