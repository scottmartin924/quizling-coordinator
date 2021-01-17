package com.quizling.controller

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import com.quizling.actor.Director
import com.quizling.actor.Director.{DirectorCommand, RespondMatchQueryFlow}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class MatchController(val director: ActorRef[DirectorCommand])(implicit actorSystem: ActorSystem[_]) {
    private implicit val ec = actorSystem.executionContext

    val routes: Route = concat(
      get {
        path("socket") {
          parameter("matchId") { matchId =>
            // FIXME Right now this timeout is killing the app..that's not good
            implicit val timeout: Timeout = 3.seconds

            // FIXME This whole section is awful. I don't think we can avoid await (although maybe I'm wrong) but
            // at least need to handle potential errors better
            val response: Future[RespondMatchQueryFlow] = director.ask[RespondMatchQueryFlow](ref => Director.RetrieveMatchFlow(matchId, ref))
            val flow: Future[Flow[Message, Message, Any]] = response.map(_.flow.get)
//              .map(handleWebSocketMessages)
//              .recoverWith(x => complete(StatusCodes.InternalServerError))

            val flowResult = Await.result(flow, 3.seconds)
            handleWebSocketMessages(flowResult)

//            val test: Route = flow.onComplete {
//              case Success(RespondMatchQueryFlow(flow)) => {
//                val newVal = flow.get
//                handleWebSocketMessages(newVal)
////                flow.fold
////                  { () => actorSystem.log.error(s"Could not find flow for match $matchId")
////                    complete(StatusCodes.InternalServerError) // FIXME Include message
////                  }
////                  //{ x => handleWebSocketMessages(x) }
////                  { x => complete(StatusCodes.OK) }
//              }
//              case Failure(ex) => {
//                actorSystem.log.info(s"Error retrieving flow for match $matchId: $ex")
//                complete(StatusCodes.InternalServerError) // FIXME Include message
//              }


            }
          }
        },
      path("test") {
        get {
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "fun"))
        }
      }
    )
}