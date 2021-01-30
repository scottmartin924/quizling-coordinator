package com.quizling.db

import org.mongodb.scala.{Completed, MongoCollection, Observer}

/**
 * Mongo insert using the mongo scala client
 *
 * @param collection the mongo collection to insert to
 * @tparam T the type of data to insert
 */
class MongoDbInsert[T](private val collection: MongoCollection[T]) extends AbstractDbInsert[T] {
  override def insert(entity: T, onSuccess: Option[() => Unit] = None, onFailure: Option[Throwable => Unit] = None): Unit = {
    collection.insertOne(entity).subscribe(new Observer[Completed] {
      // For our case onNext for insertOne always immediately followed by onComplete so we just ignore the onNext
      override def onNext(result: Completed): Unit = PartialFunction.empty
      override def onError(e: Throwable): Unit = onFailure.getOrElse(PartialFunction.empty)
      override def onComplete(): Unit = onSuccess.getOrElse(PartialFunction.empty)
    })
  }
}
