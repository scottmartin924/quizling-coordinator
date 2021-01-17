//#full-example
package com.quizling

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import com.quizling.actor.Director
import com.quizling.actor.Director.{CreateMatch, MatchConfiguration, Quiz}
import com.quizling.actor.MatchCoordinator.MatchParticipant
import com.quizling.actor.QuestionCoordinator.{Answer, Question}
import com.quizling.controller.MatchController
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util.{Failure, Success}

// TODO Get sockets setup with the actors all working correctly
// TODO Figure out marshalling/unmarshalling for socket messages (and http, but that seems easier)
  // TODO Consider using adapter(s) for messages from sockets?
// TODO Convert OO style to functional just to try to understand it better
// TODO Add a readme once everything is running and looks moderately not terrible
// TODO Add more testing (especially async testing)
// TODO Add code docs

// FIXME get a better package structure...not sure what's "standard"

object QuizlingApp extends App {
  val root = Behaviors.setup[Nothing]{ context =>
    implicit val system = context.system
    implicit val ec = system.executionContext

    val PORT_CONFIG_KEY = "system.app.http.port"
    val port = ConfigFactory.load().getInt(PORT_CONFIG_KEY)

    // For now only a single director (probably not a great idea for scaling, but the director doesn't do much atm)
    val directorActor = context.spawn(Director(), "quizling-director")

    // Since we don't handle the terminated message from the director it will stop everything with a DeathPactException which is what we want
    context.watch(directorActor)

    val routes = new MatchController(directorActor.ref)

    Http()(context.system).newServerAt("localhost", port).bind(routes.routes)
      .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds))
        .onComplete {
          case Success(value) => {
            val address = value.localAddress
            system.log.info(s"Server started at http://${address.getHostString}:${address.getPort}")
          }
          case Failure(exception) => {
            system.log.error("Failed to bind to HTTP endpoint, terminating", exception)
            system.terminate()
          }
        }

    // TODO Remove this once endpoint is setup to create matches
    // Send a messa

    directorActor ! CreateMatch(TestMatchConfiguration.matchConfiguration, Some("test"))

    Behaviors.empty
  }

  val system = ActorSystem[Nothing](root, "quizling-root-system")
}


object TestMatchConfiguration {
  private val answer1 = Answer("Fun", false)
  private val answer2 = Answer("A laugh", true)

  private val question = Question("Why did you do it?", List(answer1, answer2))
  private val participant = new MatchParticipant(participantId = "Scott")

  val quiz = new Quiz(List(question))
  val matchConfiguration = new MatchConfiguration(participants = Set(participant), quiz = quiz)
}

