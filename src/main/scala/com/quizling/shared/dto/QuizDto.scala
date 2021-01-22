package com.quizling.shared.dto

final case class QuizDto(questions: Seq[QuestionDto])

final case class QuestionDto(questionText: String, answers: Seq[AnswerDto])

final case class AnswerDto(answerText: String, isCorrect: Boolean)