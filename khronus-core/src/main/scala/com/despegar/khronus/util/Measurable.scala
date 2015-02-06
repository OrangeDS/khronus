package com.despegar.khronus.util

import com.despegar.khronus.model.{ Metric, MonitoringSupport }
import com.despegar.khronus.util.log.Logging

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Success

trait Measurable extends Logging with MonitoringSupport {

  private def now = System.currentTimeMillis()

  def measureTime[T](label: String, text: String)(block: ⇒ T): T = {
    val start = now
    val blockReturn = block
    val elapsed = now - start
    log.debug(s"$text - time spent: ${elapsed}ms")
    recordTime(label, elapsed)
    blockReturn
  }

  def measureTime[T](label: String, metric: Metric, duration: Duration)(block: ⇒ T): T = {
    if (!metric.isSystem) {
      measureTime(formatLabel(label, metric, duration), s"${p(metric, duration)} $label")(block)
    } else {
      block
    }
  }

  def measureFutureTime[T](label: String, metric: Metric, duration: Duration)(block: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] = {
    //    if (!metric.isSystem) {
    //      measureFutureTime(formatLabel(label, metric, duration), s"${p(metric, duration)} $label")(block)
    //    } else {
    //      block
    //    }
    block
  }

  def measureFutureTime[T](label: String, text: String)(block: ⇒ Future[T])(implicit ec: ExecutionContext): Future[T] = {
    //val start = now
    //block.map { result ⇒
    //val elapsed = now - start
    //log.debug(s"$text - time spent: ${elapsed}ms")
    //recordTime(label, elapsed)
    // result
    //}
    block
  }

  def formatLabel(label: String, metric: Metric, duration: Duration): String = s"$label.${metric.mtype}.${duration.length}${duration.unit}"
}
