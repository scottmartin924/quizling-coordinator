package com.quizling.shared.dto.socket

import com.quizling.actor.MatchCoordinator.{AnswerQuestion, MatchCoordinatorEvent, ParticipantReadyEvent}
import com.quizling.shared.dto.socket.Protocol.{ParticipantReady, SocketDto, SubmittedAnswerEvent}

/**
 * Converter for turning SocketDtos (which come in on websockets) to MatchCoordinatorEvent's so that they
 * can be forwarded to the MatchCoordinator to handle
 */
object SocketDtoToCoordinatorEventAdapter {

  /**
   * convert socket dto into a matchcoordinator event
   * @param dto the dto to convert
   * @return the converted dto
   */
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
