//#full-example
package com.quizling

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Behavior, PostStop, Signal}
import com.quizling.Director.{CreateMatch, MatchConfiguration, Quiz}
import com.quizling.MatchCoordinator.MatchParticipant
import com.quizling.QuestionCoordinator.{Answer, Question}

import scala.concurrent.duration._

// TODO Add a readme once everything is running and looks moderately not terrible
// TODO Add more testing (especially async testing)

// FIXME get a better package structure...not sure how that should work

// TODO Consider: should all my request have requestIds and such...think about it more
// TODO Consider using adapter(s) for messages from sockets...maybe? Idk I just don't really understand them and need to play with it some
// TODO Should every level be checking itself (e.g. should MatchCoordinator check the messages it gets to make sure matchId matches? Same question for QuestionCoordinator)
// TODO Consider: should matchcoordinator stop the questions or should they stop themselves? On the one hand, if they don't know what else to do then they should stop, on the other maybe
// match coordinator should control everything they do?
// NOTE: Both matchcoordinator and the socket actor will need references to each other (1:1) which seems strange-ish?


object TestQuiz {
  private val answer1 = Answer("Fun", false)
  private val answer2 = Answer("A laugh", true)

  private val question = Question("Why did you do it?", List(answer1, answer2))

  val quiz = new Quiz(List(question))
}

object QuizlingSupervisor {
  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing](ctx => new QuizlingSupervisor(ctx))
}

class QuizlingSupervisor(ctx: ActorContext[Nothing]) extends AbstractBehavior[Nothing](ctx) {
  context.log.info("Quizling app started")
  context.spawn(Director(), "app-director")

  override def onMessage(msg: Nothing): Behavior[Nothing] = Behaviors.unhandled

  override def onSignal: PartialFunction[Signal, Behavior[Nothing]] = {
    case PostStop => {
      context.log.info("Quizling app stopped")
      this
    }
  }
}

object QuizlingApp extends App {
  // Once everything setup can just spawn a director then an http request will talk to director, for now need to make a director and send it a message directly
  // val quizlingDirector = ActorSystem(QuizlingSupervisor(), "Quizling_Director")
  val director = ActorSystem(Director(), "quizling-director")
  val matchConfiguration = new MatchConfiguration(Set(new MatchParticipant("Scott")), TestQuiz.quiz, Some(20.second))
  director ! CreateMatch(configuration = matchConfiguration)
}
