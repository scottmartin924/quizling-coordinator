package com.quizling

import com.quizling.actor.Director.{MatchConfiguration, Quiz}
import com.quizling.actor.MatchCoordinator.MatchParticipant
import com.quizling.actor.QuestionCoordinator.{Answer, Question}


object TestData {

  object TestMatchConfiguration {
    private val answer1 = Answer("Fun", false)
    private val answer2 = Answer("A laugh", true)

    private val question = Question("Why did you do it?", List(answer1, answer2))
    private val participant = new MatchParticipant(participantId = "Scott")

    val quiz = new Quiz(List(question))
    val matchConfiguration = new MatchConfiguration(participants = Set(participant), quiz = quiz)
  }
}
