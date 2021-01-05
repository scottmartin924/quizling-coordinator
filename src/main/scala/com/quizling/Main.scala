//#full-example
package com.quizling

import akka.actor.typed.ActorSystem

import scala.concurrent.duration._
import com.quizling.Director.{CreateMatch, MatchConfiguration, Quiz}
import com.quizling.MatchCoordinator.MatchParticipant
import com.quizling.QuestionCoordinator.{Answer, Question}

// TODO Implement Director actor (it monitors all matches)
// TODO Implement MatchCoordinator (manages a single match, creates player actors, manages total score, etc)
  // Shoould be able to ask MatchCoordinator for match summary and it gives current info
// TODO Implement Player actor (tracks scores of the player, questions answered etc)

// FIXME get a better package structure...not sure how that should work

// TODO Consider: should all my request have requestIds and such...think about it more
// TODO Should every level be checking itself (e.g. should MatchCoordinator check the messages it gets to make sure matchId matches? Same question for QuestionCoordinator)
// TODO Consider: should matchcoordinator stop the questions or should they stop themselves? On the one hand, if they don't know what else to do then they should stop, on the other maybe
// match coordinator should control everything they do?


object TestQuiz {
  private val answer1 = Answer("Fun", false)
  private val answer2 = Answer("A laugh", true)

  private val question = Question("Why did you do it?", List(answer1, answer2))

  val quiz = new Quiz(List(question))
}

object QuizlingApp extends App {
  val quizlingDirector: ActorSystem[Director.DirectorCommand] = ActorSystem(Director(), "Quizling_Director")
  val matchConfiguration = new MatchConfiguration(Set(new MatchParticipant("Scott")), TestQuiz.quiz, Some(20.second))
  quizlingDirector ! CreateMatch(configuration = matchConfiguration)
}
