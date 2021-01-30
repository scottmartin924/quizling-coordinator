//#full-example
package com.quizling

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import com.quizling.actor.Director
import com.quizling.controller.MatchController
import com.quizling.db.{DbCreator, MongoDbInsert}
import com.quizling.shared.entities.MatchReport
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util.{Failure, Success}

// TODO Add more testing (especially async testing)
// TODO Add code docs
// TODO Improve logging (look into Akka logging)
// TODO Fault tolerance (Akka persistence, and more watchers, etc)

// Very low priority
// TODO Convert OO style to functional just to try to understand it better

object QuizlingApp extends App {
  val root = Behaviors.setup[Nothing]{ context =>
    implicit val system = context.system
    implicit val ec = system.executionContext

    val HTTP_CONFIG_KEY = "system.app.http"
    val httpConfig = ConfigFactory.load().getConfig(HTTP_CONFIG_KEY)


    // Create db connection and give it to director
    val collection = DbCreator.connectToMatchResultCollection()
    val directorActor = context.spawn(Director(new MongoDbInsert[MatchReport](collection)), "quizling-director")

    // Since we don't handle the terminated message from the director it will stop everything with a DeathPactException which is what we want
    context.watch(directorActor)

    val routes = new MatchController(directorActor.ref)

    Http()(context.system).newServerAt(httpConfig.getString("host"), httpConfig.getInt("port")).bind(routes.routes)
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

    Behaviors.empty
  }

  val system = ActorSystem[Nothing](root, "quizling-root-system")
}

