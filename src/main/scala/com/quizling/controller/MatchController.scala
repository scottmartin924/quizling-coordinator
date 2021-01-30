package com.quizling.controller

import java.util.UUID

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import com.quizling.actor.Director
import com.quizling.actor.Director.{DirectorCommand, MatchConfiguration, RespondMatchQueryFlow}
import com.quizling.shared.dto.{CreateMatchResponse, Link, StartMatchDto}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
 * Controller for handling http and websocket requests
 * @param director the director actor to forward messages to
 * @param actorSystem the actor system (needed by akka http)
 */
class MatchController(val director: ActorRef[DirectorCommand])(implicit actorSystem: ActorSystem[_])
  extends Directives
  with DtoJsonSupport {
    private implicit val ec = actorSystem.executionContext

    // Keep as variables so can send back to the client after starting match as a URI
    private val MATCH_PATH_PREFIX = "match"
    private val MATCH_STREAM_PATH = "stream"
    private val MATCH_STREAM_PARAM = "matchId"

    val routes: Route =
      pathPrefix(MATCH_PATH_PREFIX) {
        concat(
          get {
            path(MATCH_STREAM_PATH) {
              parameter(MATCH_STREAM_PARAM) { matchId =>
                implicit val timeout: Timeout = 10.seconds
                val response: Future[RespondMatchQueryFlow] = director.ask[RespondMatchQueryFlow](ref => Director.RetrieveMatchFlow(matchId, ref))
                val flow: Future[Flow[Message, Message, Any]] = response.map(_.flow.get)
                val flowResult = Await.result(flow, 3.seconds)
                handleWebSocketMessages(flowResult)
              }
            }
          },
          post {
            entity(as[StartMatchDto]) { request =>
              // Generate match id if not given
              val matchId = request.id.getOrElse(UUID.randomUUID()).toString
              val createMatchCmd = Director.CreateMatch(id = matchId, configuration = MatchConfiguration.fromDto(request.configuration))
              director ! createMatchCmd
              val matchStreamUri = s"$MATCH_PATH_PREFIX/$MATCH_STREAM_PATH?$MATCH_STREAM_PARAM=$matchId"
              complete(StatusCodes.Accepted, CreateMatchResponse(matchId, Map("matchStream" -> Link(matchStreamUri))))
            }
          },
          (path("test") & get) {
            complete("Hey it works")
          }
      )
    }
}