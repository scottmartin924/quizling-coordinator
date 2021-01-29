package com.quizling.shared.dto.socket

object Protocol {
  sealed trait SocketDto

  sealed trait ClientEvent extends SocketDto
  sealed trait ServerEvent extends SocketDto

  final case class AnswerSocketDto(answerId: String, answerText: String) extends SocketDto
  final case class SubmittedAnswerEvent(questionId: String, participantId: String, answer: AnswerSocketDto) extends ClientEvent
  final case class ParticipantReady(participantId: String) extends ClientEvent
  final case class QuestionSocketDto(questionId: String, questionText: String, answers: Seq[AnswerSocketDto], timer: Option[Int]) extends ServerEvent
  final case class AnswerCorrectEvent(questionId: String, answererId: String, correctAnswer: AnswerSocketDto, score: Map[String, Int]) extends ServerEvent
  final case class AnswerIncorrectEvent(questionId: String, incorrectAnswerId: String, participantId: String) extends ServerEvent
  final case class QuestionTimeOutEvent(questionId: String, correctAnswer: AnswerSocketDto, score: Map[String, Int]) extends ServerEvent
  final case object ReadyForNextQuestion extends ClientEvent

  // TODO Consider sending more detailed result here (e.g. all questions user tried to answer, # answered correctly, etc)
  final case class MatchCompleteEvent(matchId: String, score: Map[String, Int]) extends ServerEvent

  object SocketEvent {
    sealed trait WebsocketEvent extends ServerEvent
    final case object Complete extends WebsocketEvent
    final case class Failure(ex: Throwable) extends WebsocketEvent
  }
}