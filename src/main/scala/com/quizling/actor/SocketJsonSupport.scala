package com.quizling.actor

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.quizling.shared.dto.socket.Protocol._
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat, _}

trait SocketJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  private val TYPE_HINT_FIELD = "@type"

  implicit val answerSocketFormat = jsonFormat2(AnswerSocketDto)
  implicit val submittedAnswerSocketFormat = jsonFormat3(SubmittedAnswerEvent)
  implicit val participantReadySocketFormat = jsonFormat1(ParticipantReady)
  implicit val questionSocketFormat = jsonFormat4(QuestionSocketDto)
  implicit val answerCorrectSocketFormat = jsonFormat4(AnswerCorrectEvent)
  implicit val answerIncorrectSocketFormat = jsonFormat3(AnswerIncorrectEvent)
  implicit val questionTimedOutSocketFormat = jsonFormat3(QuestionTimeOutEvent)
  implicit val matchCompleteFormat = jsonFormat2(MatchCompleteEvent)

  // Seems like there is probably an easier way to handle this, but I can't figure it out from the spray-json docs
  implicit object ServerEventFormat extends RootJsonFormat[ServerEvent] {
    override def write(obj: ServerEvent): JsValue = {
      obj match {
        case qu: QuestionSocketDto => writeObject(QUESTION_TH, qu.toJson)
        case ans: AnswerCorrectEvent => writeObject(ANS_CORRECT_TH, ans.toJson)
        case ans: AnswerIncorrectEvent => writeObject(ANS_INC_TH, ans.toJson)
        case timeOut: QuestionTimeOutEvent => writeObject(QUESTION_TIMEOUT_TH, timeOut.toJson)
        case mc: MatchCompleteEvent => writeObject(MATCH_COMPLETE_TH, mc.toJson)
      }
    }

    // Delegate to SocketDto reader and cast
    override def read(json: JsValue): ServerEvent = {
      SocketDtoFormat.read(json).asInstanceOf[ServerEvent]
    }
  }

  implicit object ClientEventFormat extends RootJsonFormat[ClientEvent] {
    override def write(obj: ClientEvent): JsValue = {
      obj match {
        case sa: SubmittedAnswerEvent => writeObject(SUBMITTED_ANSWER_TH, sa.toJson)
        case pr: ParticipantReady => writeObject(PARTICIPANT_READY_TH, pr.toJson)
      }
    }

    override def read(json: JsValue): ClientEvent = {
      SocketDtoFormat.read(json).asInstanceOf[ClientEvent]
    }
  }

  implicit object SocketDtoFormat extends RootJsonFormat[SocketDto] {
    override def read(json: JsValue): SocketDto = {
      json.asJsObject.fields(TYPE_HINT_FIELD) match {
        case JsString(ANSWER_TH) => json.convertTo[AnswerSocketDto]
        case JsString(SUBMITTED_ANSWER_TH) => json.convertTo[SubmittedAnswerEvent]
        case JsString(QUESTION_TH) => json.convertTo[QuestionSocketDto]
        case JsString(ANS_CORRECT_TH) => json.convertTo[AnswerCorrectEvent]
        case JsString(ANS_INC_TH) => json.convertTo[AnswerIncorrectEvent]
        case JsString(QUESTION_TIMEOUT_TH) => json.convertTo[QuestionTimeOutEvent]
        case JsString(PARTICIPANT_READY_TH) => json.convertTo[ParticipantReady]
        case JsString(MATCH_COMPLETE_TH) => json.convertTo[MatchCompleteEvent]
      }
    }

    override def write(obj: SocketDto): JsValue = {
      obj match {
        case a: AnswerSocketDto => writeObject(ANSWER_TH, a.toJson)
        case se: ServerEvent => ServerEventFormat.write(se)
        case ce: ClientEvent => ClientEventFormat.write(ce)
      }
    }
  }

  private def writeObject(typeHint: String, jsonObj: JsValue): JsObject = {
    JsObject(jsonObj.asJsObject.fields + (TYPE_HINT_FIELD -> JsString(typeHint)))
  }

  // FIXME This is not great...but I'm not sure what else to do (bimap structure maybe...but even then tricky)
  private val ANSWER_TH = "answer"
  private val SUBMITTED_ANSWER_TH = "submitted-answer"
  private val PARTICIPANT_READY_TH = "participant-ready"
  private val QUESTION_TH = "question"
  private val ANS_CORRECT_TH = "correct-answer"
  private val ANS_INC_TH = "incorrect-answer"
  private val QUESTION_TIMEOUT_TH = "question-timeout"
  private val MATCH_COMPLETE_TH = "match-complete"
}