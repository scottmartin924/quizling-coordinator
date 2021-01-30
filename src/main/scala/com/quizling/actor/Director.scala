package com.quizling.actor

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{CompletionStrategy, OverflowStrategy}
import com.quizling.actor.Director._
import com.quizling.actor.MatchCoordinator.{MatchCoordinatorEvent, MatchParticipant}
import com.quizling.actor.QuestionCoordinator.{Question, QuizAnswer}
import com.quizling.db.ResultWriter.DatabasePersistRequest
import com.quizling.db.{AbstractDbInsert, ResultWriter}
import com.quizling.shared.dto.MatchConfigurationDto
import com.quizling.shared.dto.socket.Protocol.{ServerEvent, SocketDto, SocketEvent}
import com.quizling.shared.dto.socket.SocketDtoToCoordinatorEventAdapter
import com.quizling.shared.entities.MatchReport
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

/**
 * Director actor setup and actor events
 */
object Director {
  def apply(dbInsertWriter: AbstractDbInsert[MatchReport]): Behavior[Director.DirectorCommand] = {
    Behaviors.setup(ctx => new Director(ctx, dbInsertWriter))
  }

  sealed trait DirectorCommand
  final case class CreateMatch(configuration: MatchConfiguration, id: String) extends DirectorCommand
  final case class ForwardMatchMessage(matchId: String) // Messages to forward to matches
  final case class MatchCompleted(matchId: String, matchResult: MatchResult) extends DirectorCommand

  sealed trait DirectorResponse
  final case class RetrieveMatchCoordinator(matchId: String, replyTo: ActorRef[RespondMatchQueryCoordinator]) extends DirectorCommand
  final case class RespondMatchQueryCoordinator(actorRef: Option[ActorRef[MatchCoordinatorEvent]]) extends DirectorResponse
  final case class RespondMatchQueryFlow(flow: Option[Flow[Message, Message, Any]]) extends DirectorResponse
  final case class RetrieveMatchFlow(matchId: String, replyTo: ActorRef[RespondMatchQueryFlow]) extends DirectorCommand

  final class MatchDependencies(val coordinator: ActorRef[MatchCoordinatorEvent],
                                val socketFlow: Flow[Message, Message, Any])
  final case class MatchResult(score: Map[String, Int])

  // Domain objects for director
  final case class Quiz(questions: Seq[Question])
  final case class MatchConfiguration(participants: Set[MatchParticipant], quiz: Quiz, timer: Option[FiniteDuration]) {
    require(participants.nonEmpty)
  }
  // MatchConfigurationDto -> MatchConfiguration mapper (probably a nicer way to do this...maybe an implicit conversion but that seems overly confusing)
  // If we had more of these better to define a mapper for each class (or get a library that does) but that seems like overkill here
  object MatchConfiguration {
    def fromDto(dto: MatchConfigurationDto): MatchConfiguration = {
      val participants: Set[MatchParticipant] = dto.participants.map(x => MatchParticipant(x.participantId))
      val questions = dto.quiz.questions.map(x => {
        val answers = x.answers.zipWithIndex.map(x => QuizAnswer(answerId = x._2.toString, answerText = x._1.answerText, isCorrect = x._1.isCorrect))
        Question(questionText = x.questionText, answers = answers)
      })
      val timer = Duration(dto.timer.getOrElse(60), TimeUnit.SECONDS) // If no duration given then defaults to 60 seconds
      MatchConfiguration(participants, Quiz(questions), Some(timer))
    }
  }
}

/**
 * Director actor to handle all matches. This is a top-level actor for which there's only one running per-instance. It
 * will delegate work to MatchCoordinators as matches are started and write db results as matches complete
 * @param ctx the actorcontext for the actor system
 * @param dbInserter the database inserter to write match results to
 */
class Director(ctx: ActorContext[Director.DirectorCommand], dbInserter: AbstractDbInsert[MatchReport])
  extends AbstractBehavior[Director.DirectorCommand](ctx)
  with SocketJsonSupport {
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
        dbWriter ! DatabasePersistRequest(MatchReport(matchId = id, score = result.score))
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
    // Could use lots of improvements. Here are some TODOs
    // TODO General refactoring: probably a cleaner way to create this flow. Need more akka-streams knowledge
    // TODO Implement backpressure for the sink (then change to ActorSink.actorRefWithBackpressure)

    implicit val ec = ctx.executionContext
    val STREAMED_MESSAGE_TIMEOUT = 5 seconds

    // Create match flow
    val (socketWriter, source): (ActorRef[ServerEvent], Source[ServerEvent, NotUsed]) = ActorSource.actorRef[ServerEvent](
      completionMatcher = { case SocketEvent.Complete => CompletionStrategy.immediately },
      failureMatcher = { case SocketEvent.Failure(ex) =>
        println(s"WS stream failed with cause $ex")
        ex
      },
      100,
      OverflowStrategy.dropHead
    ).toMat(BroadcastHub.sink)(Keep.both)
      .run()

    val matchCoordinator: ActorRef[MatchCoordinatorEvent] = ctx.spawn(MatchCoordinator(matchId, config, ctx.self, socketWriter), matchId)
    val socketListener: ActorRef[SocketDto] = ctx.spawn(SocketEventListener[MatchCoordinatorEvent](matchCoordinator, SocketDtoToCoordinatorEventAdapter.dtoToEvent), s"match-$matchId-socket-listener")
    val (_, actorSource): (NotUsed, Source[Message, NotUsed]) = source.map[Message](x => TextMessage(x.toJson.toString))
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

    val actorSink: Sink[SocketDto, NotUsed] = ActorSink.actorRef[SocketDto](socketListener, SocketEvent.Complete, ex => SocketEvent.Failure(ex))
    val mapToSink: Sink[Message, NotUsed] = Flow[Message].collect[SocketDto] {
      case TextMessage.Strict(msg) => msg.parseJson.convertTo[SocketDto]
      case TextMessage.Streamed(ts) =>  {
        // I'm not sure this is the right way to handle streamed messages (we shouldn't really have any anyways though)
        val futureDto: Future[SocketDto] = ts.completionTimeout(timeout = STREAMED_MESSAGE_TIMEOUT)
          .runFold(new StringBuilder())((b,s) => b.append(s))
          .map(b => b.toString)
          .map(s => s.parseJson.convertTo[SocketDto])

        Await.result(futureDto, atMost = STREAMED_MESSAGE_TIMEOUT)
      }
      case bm: BinaryMessage => {
        // NOTE: Null isn't allowed in flows so this will be ignored
        bm.dataStream.runWith(Sink.ignore) // Ignore binary messages but drain
        null
      }
    }.to(actorSink)

    val flow: Flow[Message, Message, Any] = Flow.fromSinkAndSource(mapToSink, actorSource)
      .log("fun")
      .recover(ex => {
        TextMessage(ex.toString)
      })
    new MatchDependencies(matchCoordinator, flow)
  }
}
