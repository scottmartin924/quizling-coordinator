package com.quizling.shared.dto

/**
 * Dto for a quiz
 * @param questions the questions on the quiz
 */
final case class QuizDto(questions: Seq[QuestionConfigurationDto])

/**
 * Configuration for individual question
 * @param questionText the text of the question
 * @param answers the possible answers for the question
 */
final case class QuestionConfigurationDto(questionText: String, answers: Seq[AnswerConfigurationDto])

/**
 * Configuration for answer
 * @param answerText text of the answer
 * @param isCorrect if the answer is correct
 */
final case class AnswerConfigurationDto(answerText: String, isCorrect: Boolean)