package com.kiktibia.deathtracker
package tibiadata

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.kiktibia.deathtracker.tibiadata.response.{CharacterResponse, WorldResponse}
import com.typesafe.scalalogging.StrictLogging
import spray.json.JsonParser.ParsingException

import scala.concurrent.{ExecutionContextExecutor, Future}
import spray.json.DeserializationException

class TibiaDataClient extends JsonSupport with StrictLogging {

  implicit private val system: ActorSystem = ActorSystem()
  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val worldUrl = "https://api.tibiadata.com/v4/world/Nefera"
  private val characterUrl = "https://api.tibiadata.com/v4/character/"

  def getWorld(): Future[WorldResponse] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = worldUrl))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[WorldResponse]
    } yield unmarshalled
  }

  // import scala.concurrent.duration._
  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$characterUrl${name.replaceAll(" ", "%20")}"))
      decoded = decodeResponse(response)
      // s <- decoded.entity.toStrict(1.second).map(_.data.utf8String)
      // _ = println(s)
      unmarshalled <- Unmarshal(decoded).to[CharacterResponse].map(Right(_))
        .recover { case e @ (_: ParsingException | _: DeserializationException) =>
          val errorMessage = s"Failed to parse character with name $name"
          logger.warn(errorMessage)
          Left(errorMessage)
        }
    } yield unmarshalled
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
