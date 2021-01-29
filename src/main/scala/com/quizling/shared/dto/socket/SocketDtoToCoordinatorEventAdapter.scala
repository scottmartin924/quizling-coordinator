package com.quizling.shared.dto.socket

import com.quizling.actor.MatchCoordinator.{AnswerQuestion, MatchCoordinatorEvent, ParticipantReadyEvent}
import com.quizling.shared.dto.socket.Protocol.{ParticipantReady, SocketDto, SubmittedAnswerEvent}

object SocketDtoToCoordinatorEventAdapter {

  def dtoToEvent(dto: SocketDto): MatchCoordinatorEvent = {
    dto match {
      case SubmittedAnswerEvent(questionId, participantId, answer) => {
        AnswerQuestion(questionId = questionId,
          participantId = participantId,
          answerId = answer.answerId,
          answer = answer.answerText)
      }
      case ParticipantReady(partId) => ParticipantReadyEvent(partId)
    }
  }
}
