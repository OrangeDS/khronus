package com.despegar.khronus.store

import java.util.concurrent.TimeUnit

import com.despegar.khronus.util.{ SameThreadExecutionContext, ConcurrencySupport }
import com.despegar.khronus.util.log.Logging

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

trait Snapshot[T] extends Logging with ConcurrencySupport {

  def snapshotName: String

  def initialValue: T

  @volatile
  var snapshot: T = initialValue

  private val scheduledPool = scheduledThreadPool(s"snapshot-reload-scheduled-worker")

  //use the scheduledPool only to start the main thread. the remain operations like getFreshData() will run on this one
  implicit val context: ExecutionContext = executionContext("snapshot-reload-worker")

  def startSnapshotReloads() = {
    Try {
      snapshot = Await.result(getFreshData()(context), 5 seconds)
    }
    scheduledPool.scheduleAtFixedRate(reload(), 5, 5, TimeUnit.SECONDS)
  }

  private def reload() = new Runnable {
    override def run(): Unit = {
      try {
        getFreshData().onComplete {
          case Success(data) ⇒ snapshot = data
          case Failure(t)    ⇒ log.error("Error reloading data", t)
        }
      } catch {
        case reason: Throwable ⇒ log.error("Unexpected error reloading data", reason)
      }
    }
  }

  def getFromSnapshot: T = snapshot

  def getFreshData()(implicit executor: ExecutionContext): Future[T]

}

