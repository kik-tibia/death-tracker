package com.kiktibia.deathtracker

import akka.actor.Cancellable
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.Attributes
import akka.stream.Materializer
import akka.stream.Supervision
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.RunnableGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.kiktibia.deathtracker.tibiadata.FileUtils
import com.kiktibia.deathtracker.tibiadata.TibiaDataClient
import com.kiktibia.deathtracker.tibiadata.response.CharacterResponse
import com.kiktibia.deathtracker.tibiadata.response.Deaths
import com.kiktibia.deathtracker.tibiadata.response.WorldResponse
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.TextChannel

import java.io.File
import java.time.ZonedDateTime
import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import net.dv8tion.jda.api.entities.MessageEmbed

class DeathTrackerStream(guilds: List[Guild])(implicit ex: ExecutionContextExecutor, mat: Materializer)
    extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  case class CharKey(char: String, time: ZonedDateTime)

  case class CharDeath(char: CharacterResponse, death: Deaths)

  private val recentDeaths = mutable.Set.empty[CharKey]
  private val recentOnline = mutable.Set.empty[CharKey]

  private val tibiaDataClient = new TibiaDataClient()

  // 30 minutes for a death to count as recent enough to be worth notifying
  private val deathRecentDuration = 30 * 60
  // 10 minutes for a character to still be checked for deaths after logging off
  private val onlineRecentDuration = 10 * 60

  private val trackedPlayerFile = new File(Config.trackedPlayerFile)
  private val notableCreaturesFile = new File(Config.notableCreaturesFile)

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the DeathTrackerStream:", e)
    Supervision.Resume
  }
  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)

  private lazy val sourceTick = Source.tick(2.seconds, 25.seconds, ())

  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info("Running stream")
    tibiaDataClient.getWorld() // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[WorldResponse].mapAsync(1) { worldResponse =>
    val now = ZonedDateTime.now()
    val online: List[String] = worldResponse.world.online_players.map(_.name)
    recentOnline.filterInPlace(i => !online.contains(i.char)) // Remove existing online chars from the list...
    recentOnline.addAll(online.map(i => CharKey(i, now))) // ...and add them again, with an updated online time
    val trackedPlayers = getTrackedPlayers()
    val charsToCheck: Set[String] = recentOnline.map(_.char).toSet ++ trackedPlayers
    Source(charsToCheck).mapAsyncUnordered(16)(tibiaDataClient.getCharacter).runWith(Sink.collection)
      .map(_.flatMap(_.toOption).toSet)
  }.withAttributes(logAndResume)

  private lazy val scanForDeaths = Flow[Set[CharacterResponse]].mapAsync(1) { characterResponses =>
    val now = ZonedDateTime.now()
    val newDeaths = characterResponses.flatMap { char =>
      val deaths: List[Deaths] = char.character.deaths.getOrElse(List.empty)
      deaths.flatMap { death =>
        val deathTime = ZonedDateTime.parse(death.time)
        val deathAge = java.time.Duration.between(deathTime, now).getSeconds
        val charDeath = CharKey(char.character.character.name, deathTime)
        if (deathAge < deathRecentDuration && !recentDeaths.contains(charDeath)) {
          recentDeaths.add(charDeath)
          Some(CharDeath(char, death))
        } else None
      }
    }
    Future.successful(newDeaths)
  }.withAttributes(logAndResume)

  def deathsToEmbed(deaths: List[CharDeath]): List[MessageEmbed] = deaths.sortBy(_.death.time).map { charDeath =>
    val charName = charDeath.char.character.character.name
    val killer = charDeath.death.killers.last.name
    val epochSecond = ZonedDateTime.parse(charDeath.death.time).toEpochSecond
    new EmbedBuilder().setTitle(s"$charName ${vocEmoji(charDeath.char)}", charUrl(charName))
      .setDescription(s"Killed at level ${charDeath.death.level.toInt} by **$killer**\nKilled at <t:$epochSecond>")
      .setThumbnail(creatureImageUrl(killer)).setColor(13773097).build()
  }

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharDeath]].mapAsync(1) { charDeaths =>
    val notableCreatures = FileUtils.getLines(notableCreaturesFile).filter(_.nonEmpty).filterNot(_.startsWith("#"))
    // Filter only the interesting deaths (nemesis bosses, rare bestiary)
    val (notableDeaths, normalDeaths) = charDeaths.toList.partition { charDeath =>
      notableCreatures.exists(c => charDeath.death.killers.last.name.toLowerCase.endsWith(c))
    }

    logger.info(s"New notable deaths: ${notableDeaths.length}")
    notableDeaths.foreach(d => logger.info(s"${d.char.character.character.name} - ${d.death.killers.last.name}"))
    logger.info(s"New normal deaths: ${normalDeaths.length}")
    normalDeaths.foreach(d => logger.info(s"${d.char.character.character.name} - ${d.death.killers.last.name}"))

    val notableEmbeds = deathsToEmbed(notableDeaths)
    val normalEmbeds = deathsToEmbed(normalDeaths)

    guilds.foreach { guild =>
      val channels = guild.getTextChannels().asScala
      channels.find(c => c.getName().endsWith("alert") || c.getName().endsWith("alerts")).foreach { channel =>
        // Send the embeds one at a time, otherwise some don't get sent if sending a lot at once
        notableEmbeds.foreach { embed => channel.sendMessageEmbeds(embed).queue() }
        if (notableEmbeds.nonEmpty) { channel.sendMessage("@here").queue() }
      }
      channels.find(c => c.getName().endsWith("deaths")).foreach { channel =>
        normalEmbeds.foreach { embed => channel.sendMessageEmbeds(embed).queue() }
      }
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
    tibiaDataClient.cleanupMap(recentOnline.map(_.char).toSet ++ getTrackedPlayers())
  }

  private def vocEmoji(char: CharacterResponse): String = {
    val voc = char.character.character.vocation.toLowerCase.split(' ').last
    voc match {
      case "knight" => ":shield:"
      case "druid" => ":snowflake:"
      case "sorcerer" => ":fire:"
      case "paladin" => ":bow_and_arrow:"
      case "monk" => ":fist:"
      case "none" => ":hatching_chick:"
      case _ => ""
    }
  }

  private def charUrl(char: String): String = s"https://www.tibia.com/community/?name=${char.replaceAll(" ", "+")}"

  private def creatureImageUrl(creature: String): String = {
    val finalCreature = Config.creatureUrlMappings.getOrElse(
      creature.toLowerCase, {
        // Capitalise the start of each word, including after punctuation e.g. "Mooh'Tah Warrior", "Two-Headed Turtle"
        val rx1 = """([^\w]\w)""".r
        val parsed1 = rx1.replaceAllIn(creature, m => m.group(1).toUpperCase)

        // Lowercase the articles, prepositions etc., e.g. "The Voice of Ruin"
        val rx2 = """( A| Of| The| In| On| To| And| With| From)(?=( ))""".r
        val parsed2 = rx2.replaceAllIn(parsed1, m => m.group(1).toLowerCase)

        // Replace spaces with underscores and make sure the first letter is capitalised
        parsed2.replaceAll(" ", "_").capitalize
      }
    )
    s"https://tibia.fandom.com/wiki/Special:Redirect/file/$finalCreature.gif"
  }

  private def getTrackedPlayers() = FileUtils.getLines(trackedPlayerFile).filter(_.nonEmpty)
    .filterNot(_.startsWith("#"))

  lazy val stream: RunnableGraph[Cancellable] = sourceTick via getWorld via getCharacterData via scanForDeaths via
    postToDiscordAndCleanUp to Sink.ignore

}
