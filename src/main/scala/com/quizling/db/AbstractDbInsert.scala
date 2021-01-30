package com.quizling.db


/**
 * Insert statement to interact with database
 * NOTE: The fact that the mongo client uses observables
 * sort of dictates that you have to pass in completion methods.
 * Not sure this is very "generic" in general
 *
 * @tparam T the type of data to insert
 */
abstract class AbstractDbInsert[T] {

  /**
   * Insert object into database
   *
   * @param entity the object to insert
   * @param onSuccess function to be run on insert success (if any)
   * @param onFailure function to be run on insert failure (if any)
   */
  def insert(entity: T, onSuccess: Option[() => Unit] = None, onFailure: Option[Throwable => Unit] = None)
}
