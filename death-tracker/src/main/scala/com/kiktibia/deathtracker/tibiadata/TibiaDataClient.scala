package com.kiktibia.deathtracker
package tibiadata

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.coding.Coders
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.headers.HttpEncodings
import org.apache.pekko.http.scaladsl.model.headers.{Date => DateHeader}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import com.kiktibia.deathtracker.tibiadata.response.CharacterResponse
import com.kiktibia.deathtracker.tibiadata.response.WorldResponse
import com.typesafe.scalalogging.StrictLogging
import spray.json.DeserializationException
import spray.json.JsonParser.ParsingException
import spray.json._

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future

class TibiaDataClient extends JsonSupport with StrictLogging {

  implicit private val system: ActorSystem = ActorSystem()
  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val worldUrl = "https://api.tibiadata.com/v5/world/Nefera"
  private val characterUrl = "https://api.tibiadata.com/v5/character/"
  private val characterMap = mutable.Map.empty[String, ZonedDateTime]

  def getWorld(): Future[WorldResponse] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = worldUrl))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[WorldResponse]
    } yield unmarshalled
  }

  def cleanupMap(chars: Set[String]): Unit = characterMap.filterInPlace { case (k, cacheTime) => chars.contains(k) }

  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$characterUrl${name.replaceAll(" ", "%20")}"))

      dateHeader = response.header[DateHeader].map { h =>
        val local = LocalDateTime.parse(h.date.toIsoDateTimeString())
        ZonedDateTime.of(local, ZoneId.of("GMT"))
      }
      characterCachedDate = characterMap.get(name)

      unmarshalled <- (dateHeader, characterCachedDate) match {
        case (Some(date), Some(cachedDate)) if date == cachedDate =>
          response.discardEntityBytes()
          Future.successful(Left("Cache hit"))
        case (Some(date), Some(cachedDate)) if date != cachedDate =>
          characterMap += (name -> date)
          unmarshalCharacter(response, name)
        case (Some(date), None) =>
          characterMap += (name -> date)
          unmarshalCharacter(response, name)
        case _ =>
          response.discardEntityBytes()
          Future.successful(Left("No header"))
      }
    } yield unmarshalled
  }

 import scala.concurrent.duration._

  private def unmarshalCharacter(response: HttpResponse, name: String) = {
    val decoded = decodeResponse(response)
    decoded.entity.toStrict(5.seconds).flatMap { strict =>
      Unmarshal(strict).to[CharacterResponse].map(Right(_)).recover {
        case e =>
          val body = strict.data.utf8String
          val headers = decoded.headers.map(h => s"${h.name}: ${h.value}").mkString(", ")
          logger.warn(s"Failed to parse character $name: ${e.getMessage}, headers: [$headers], body: ${body.take(1000)}")
          Left(s"Failed to parse: ${e.getMessage}")
      }
    }
  }

  private def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip => Coders.Gzip
      case HttpEncodings.deflate => Coders.Deflate
      case HttpEncodings.identity => Coders.NoCoding
      case other =>
        logger.warn(s"Unknown encoding [$other], not decoding")
        Coders.NoCoding
    }

    decoder.decodeMessage(response)
  }

}
