package com.quizling.shared.dto

/**
 * Match configuration transfer object
 *
 * @param participants the set of participants in the match
 * @param quiz the quiz to be given during the match
 * @param timer the time given for each question in seconds (if not given then defaults to 60 in the controller)
 */
final case class MatchConfigurationDto(participants: Set[MatchParticipantDto], quiz: QuizDto, timer: Option[Int])
