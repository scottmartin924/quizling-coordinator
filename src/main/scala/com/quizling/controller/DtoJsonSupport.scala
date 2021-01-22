package com.quizling.controller

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.quizling.shared.dto.{AnswerDto, CreateMatchResponse, Link, MatchConfigurationDto, MatchParticipantDto, QuestionDto, QuizDto, StartMatchDto}
import spray.json.DefaultJsonProtocol

trait DtoJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val answerFormat = jsonFormat2(AnswerDto)
  implicit val questionFormat = jsonFormat2(QuestionDto)
  implicit val quizFormat = jsonFormat1(QuizDto)
  implicit val matchParticipantFormat = jsonFormat1(MatchParticipantDto)
  implicit val matchConfigurationFormat = jsonFormat3(MatchConfigurationDto)
  implicit val startMatchFormat = jsonFormat2(StartMatchDto)
  implicit val linkFormat = jsonFormat1(Link)
  implicit val createMatchResponseFormat = jsonFormat2(CreateMatchResponse)
}
