package com.quizling.actor

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{CompletionStrategy, OverflowStrategy}
import com.quizling.actor.Director._
import com.quizling.actor.MatchCoordinator.{MatchCoordinatorEvent, MatchParticipant}
import com.quizling.actor.QuestionCoordinator.{Answer, Question}
import com.quizling.actor.SocketEvent.{SocketMessage, WebsocketEvent}
import com.quizling.db.ResultWriter.DatabasePersistRequest
import com.quizling.db.{AbstractDbInsert, ResultWriter}
import com.quizling.shared.dto.MatchConfigurationDto
import com.quizling.shared.entities.MatchReport

import scala.concurrent.duration.{Duration, FiniteDuration}

object Director {

  val DirectorServiceKey = ServiceKey[DirectorCommand]("quizling-director")

  // FIXME Once db is wrapped in a more generic wrapper this should take in a db connection then let director create the actor
  def apply(dbInsertWriter: AbstractDbInsert[MatchReport]): Behavior[Director.DirectorCommand] = {
    Behaviors.setup(ctx => new Director(ctx, dbInsertWriter))
  }

  sealed trait DirectorCommand
  final case class CreateMatch(configuration: MatchConfiguration, id: String) extends DirectorCommand
  final case class ForwardMatchMessage(matchId: String) // Messages to forward to matches
  final case class MatchCompleted(matchId: String, matchResult: MatchResult) extends DirectorCommand

  sealed trait DirectorResponse
  // I don't really like that these two are separate at the moment...not sure what to do about it
  // Query for match coordinator
  final case class RetrieveMatchCoordinator(matchId: String, replyTo: ActorRef[RespondMatchQueryCoordinator]) extends DirectorCommand
  final case class RespondMatchQueryCoordinator(actorRef: Option[ActorRef[MatchCoordinatorEvent]]) extends DirectorResponse

  // FIXME Shouldn't be a director command
  // Query for match flow
  final case class RespondMatchQueryFlow(flow: Option[Flow[Message, Message, Any]]) extends DirectorResponse
  final case class RetrieveMatchFlow(matchId: String, replyTo: ActorRef[RespondMatchQueryFlow]) extends DirectorCommand

  final case class Quiz(val questions: Seq[Question])
  final case class MatchConfiguration(val participants: Set[MatchParticipant], val quiz: Quiz, val timer: Option[FiniteDuration] = None) {
    require(!participants.isEmpty)
  }

  // MatchConfigurationDto -> MatchConfiguration mapper (probably a nicer way to do this...maybe an implicit conversion but that seems overly confusing)
  // If we had more of these better to define a mapper for each class (or get a library that does) but that seems like overkill here
  object MatchConfiguration {
    def fromDto(dto: MatchConfigurationDto): MatchConfiguration = {
      val participants: Set[MatchParticipant] = dto.participants.map(x => MatchParticipant(x.participantId))
      val questions = dto.quiz.questions.map(x => {
        val answers = x.answers.map(x => Answer(answerText = x.answerText, isCorrect = x.isCorrect))
        Question(questionText = x.questionText, answers = answers)
      })
      val timer = Duration(dto.timer.getOrElse(60), TimeUnit.SECONDS) // If no duration given then defaults to 60 seconds
      MatchConfiguration(participants, Quiz(questions), Some(timer))
    }
  }

  // FIXME Make these have socket writer and socket receiver classes once I make those and then
  // have active matches have match dependencies as the value and create method to return flow or not found if
  // active match doesn't exist (and need socket writer and receiver classes to make sense...this is not going well
  final class MatchDependencies(val coordinator: ActorRef[MatchCoordinatorEvent],
                                val socketFlow: Flow[Message, Message, Any]
                               )
  final class MatchResult(val matchId: String) //TODO Insert matchresult info as well
}

class Director(ctx: ActorContext[Director.DirectorCommand], dbInserter: AbstractDbInsert[MatchReport]) extends AbstractBehavior[Director.DirectorCommand](ctx) {
  private implicit val system: ActorSystem[Nothing] = ctx.system
  private var activeMatches: Map[String, MatchDependencies] = Map.empty[String, MatchDependencies]
  private val dbWriter = ctx.spawn(ResultWriter(dbInserter), "db-writer")

  override def onMessage(msg: DirectorCommand): Behavior[DirectorCommand] = {
    msg match {
      case CreateMatch(configuration, matchId) => {
        context.log.info(s"Creating match $matchId")
        val matchDependencies: MatchDependencies = createMatch(matchId, configuration)
        activeMatches += (matchId -> matchDependencies)
        this
      }

      case MatchCompleted(id, result) => {
        context.log.info(s"Match $id completed")
        dbWriter ! DatabasePersistRequest(MatchReport(matchId = id))
        activeMatches -= id
        this
      }

      case RetrieveMatchCoordinator(matchId, replyTo) => {
        val responseMsg = activeMatches.get(matchId)
          .fold{ RespondMatchQueryCoordinator(None) } { x => RespondMatchQueryCoordinator(Some(x.coordinator)) }
        replyTo ! responseMsg
        this
      }

      case RetrieveMatchFlow(matchId, replyTo) => {
        val respondMsg: RespondMatchQueryFlow = activeMatches.get(matchId)
          .fold{ RespondMatchQueryFlow(None) } { x => Director.RespondMatchQueryFlow(Some(x.socketFlow)) }
        replyTo ! respondMsg
        this
      }
    }
  }

  // Create match and flow
  private def createMatch(matchId: String, config: MatchConfiguration): MatchDependencies = {
    // Create match flow
    // TODO Refactor this. First, probably a cleaner way to create the flow. Second shouldn't all be in this method
    val (socketWriter, source): (ActorRef[WebsocketEvent], Source[WebsocketEvent, NotUsed]) = ActorSource.actorRef[WebsocketEvent](
      completionMatcher = { case SocketEvent.Complete => CompletionStrategy.immediately },
      failureMatcher = { case SocketEvent.Failure(ex) =>
        println(s"WS stream failed with cause $ex")
        ex
      },
      100,
      OverflowStrategy.dropHead
    ).toMat(BroadcastHub.sink)(Keep.both)
      .run()

    val matchCoordinator = ctx.spawn(MatchCoordinator(matchId, config, ctx.self, socketWriter), matchId)

    // FIXME Add appropriate message adapter (probably not in-line but in an object somewhere)
    val socketListener = ctx.spawn(SocketEventListener[MatchCoordinatorEvent](matchCoordinator, PartialFunction.empty), s"match-$matchId-socket-listener")
//    val socketListener = ctx.spawn(SocketEventListener[MatchCoordinatorEvent](matchCoordinator, {
//      case SocketMessage(msg) => RemoveMeFixMessage(msg)
//      case _ => RemoveMeFixMessage("Not a socket message with a message")
//    }), s"match-$matchId-socket-listener")

    // Create match flow (This is hear for fixing connection termination issue)...honestly no idea what's going on with it
    // FIXME Make this exhaustive
    val (_, actorSource): (NotUsed, Source[Message, NotUsed]) = source.map[Message] {
      case SocketMessage(message) => TextMessage(s"Message: $message")
    }.toMat(BroadcastHub.sink)(Keep.both)
      .run()



    // FIXME Would like to implement backpressure here if possible
    val actorSink: Sink[WebsocketEvent, NotUsed] = ActorSink.actorRef(socketListener, SocketEvent.Complete, ex => SocketEvent.Failure(ex))
    val lhs: Sink[Message, NotUsed] = Flow[Message].collect {
      case TextMessage.Strict(msg) => {
        SocketMessage(msg)
      }// FIXME Handle streamed message
      case bm: BinaryMessage => {
        bm.dataStream.runWith(Sink.ignore)
        null // Null values ignored by stream
      }
    }.to(actorSink)

    val flow: Flow[Message, Message, Any] = Flow.fromSinkAndSource(lhs, actorSource)
      .log("fun")
      .recover(ex => {
        TextMessage(ex.toString)
      })
    new MatchDependencies(matchCoordinator, flow)
  }
}
