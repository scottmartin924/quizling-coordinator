package com.quizling.shared.entities

// Base class for dbentities, nothing here at the moment but could have things like common audit fields
trait DbEntity

case class MatchReport(matchId: String, score: Map[String, Int]) extends DbEntity
