package com.quizling.controller

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.quizling.shared.dto.{AnswerConfigurationDto, CreateMatchResponse, Link, MatchConfigurationDto, MatchParticipantDto, QuestionConfigurationDto, QuizDto, StartMatchDto}
import spray.json.DefaultJsonProtocol

trait DtoJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val answerFormat = jsonFormat2(AnswerConfigurationDto)
  implicit val questionFormat = jsonFormat2(QuestionConfigurationDto)
  implicit val quizFormat = jsonFormat1(QuizDto)
  implicit val matchParticipantFormat = jsonFormat1(MatchParticipantDto)
  implicit val matchConfigurationFormat = jsonFormat3(MatchConfigurationDto)
  implicit val startMatchFormat = jsonFormat2(StartMatchDto)
  implicit val linkFormat = jsonFormat1(Link)
  implicit val createMatchResponseFormat = jsonFormat2(CreateMatchResponse)
}
