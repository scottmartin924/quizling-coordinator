package com.quizling.shared.dto

final case class QuizDto(questions: Seq[QuestionConfigurationDto])

final case class QuestionConfigurationDto(questionText: String, answers: Seq[AnswerConfigurationDto])

final case class AnswerConfigurationDto(answerText: String, isCorrect: Boolean)