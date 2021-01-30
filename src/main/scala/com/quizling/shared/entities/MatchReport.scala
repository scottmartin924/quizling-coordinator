package com.quizling.shared.entities

// Base class for dbentities, nothing here at the moment but could have things like common audit fields
trait DbEntity

// NOTE: This will be added to (should probably have all answers that were submitted or at least data on
// which users submitted answers, how often they were correct, etc)
/**
 * Final report of a match with all details about what happened in the match
 * @param matchId the match id
 * @param score the final match score
 */
case class MatchReport(matchId: String, score: Map[String, Int]) extends DbEntity
