/*
 * =========================================================================================
 * Copyright © 2014 the metrik project <https://github.com/hotels-tech/metrik>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package com.despegar.metrik.store

import java.nio.ByteBuffer
import java.util.concurrent.Executors

import com.despegar.metrik.model.{ StatisticSummary, Metric, Summary }
import com.despegar.metrik.util.Logging
import com.netflix.astyanax.model.{ ColumnList, ColumnFamily }
import com.netflix.astyanax.serializers.{ LongSerializer, StringSerializer }

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import com.netflix.astyanax.query.RowQuery
import java.lang
import scala.annotation.tailrec
import scala.collection.mutable
import com.netflix.astyanax.connectionpool.OperationResult

case class Slice(from: Long, to: Long, reverseOrder: Boolean = false)

trait SummaryStoreSupport[T <: Summary] {
  def summaryStore: SummaryStore[T]
}

trait SummaryStore[T <: Summary] extends Logging {
  private val LIMIT = 1000
  private val INFINITE = 1L

  def windowDurations: Seq[Duration]

  def getColumnFamilyName(duration: Duration): String

  def deserialize(timestamp: Long, buffer: ByteBuffer): T

  def serializeSummary(summary: T): ByteBuffer

  lazy val columnFamilies = windowDurations.map(duration ⇒ (duration, ColumnFamily.newColumnFamily(getColumnFamilyName(duration), StringSerializer.get(), LongSerializer.get()))).toMap

  def initialize = columnFamilies.foreach(cf ⇒ Cassandra.createColumnFamily(cf._2))

  implicit val asyncExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(50))

  def getKey(metric: Metric, windowDuration: Duration): String = metric.name

  private def now = System.currentTimeMillis()

  def ifNotEmpty(col: Seq[Any])(f: ⇒ Unit): Future[Unit] = {
    if (col.size > 0) {
      Future { f }
    } else {
      Future.successful(())
    }
  }

  def store(metric: Metric, windowDuration: Duration, summaries: Seq[T]): Future[Unit] = {
    ifNotEmpty(summaries) {
      log.debug(s"$metric - Storing ${summaries.size} summaries ($summaries) of $windowDuration")
      val mutation = Cassandra.keyspace.prepareMutationBatch()
      val columns = mutation.withRow(columnFamilies(windowDuration), getKey(metric, windowDuration))
      summaries.foreach(summary ⇒ {
        columns.putColumn(summary.timestamp.ms, serializeSummary(summary), ttl(windowDuration))
      })
      mutation.execute
    }
  }

  def sliceUntilNow(metric: Metric, windowDuration: Duration): Future[Seq[T]] = {
    val asyncResult = Future {
      Cassandra.keyspace.prepareQuery(columnFamilies(windowDuration))
        .getKey(getKey(metric, windowDuration))
        .withColumnRange(INFINITE, now, false, LIMIT)
        .execute()
        .getResult()
        .asScala
    }

    asyncResult map { slice ⇒
      slice.map(column ⇒ deserialize(column.getName, column.getByteBufferValue)).toSeq
    }
  }

  def readAll(windowDuration: Duration, key: String, slice: Slice, count: Int): Future[Seq[T]] = Future {
    log.info(s"Reading from Cassandra: Cf: $windowDuration - Key: $key - From: ${slice.from} - To: ${slice.to} - Reverse: ${slice.reverseOrder} - Max results: $count")

    val query: RowQuery[String, lang.Long] = Cassandra.keyspace.prepareQuery(columnFamilies(windowDuration))
      .getKey(key)
      .withColumnRange(slice.from, slice.to, slice.reverseOrder, count)
      .autoPaginate(true)

    readRecursive(Vector.newBuilder[T])(() ⇒ query.execute())
  }

  @tailrec
  private def readRecursive(resultBuilder: mutable.Builder[T, Vector[T]])(operationResult: () ⇒ OperationResult[ColumnList[lang.Long]]): Seq[T] = {
    val result = operationResult().getResult.asScala

    if (result.isEmpty) { resultBuilder.result().toSeq }
    else {
      result.foldLeft(resultBuilder) { (builder, column) ⇒
        builder += deserialize(column.getName(), column.getByteBufferValue)
      }
      readRecursive(resultBuilder)(operationResult)
    }
  }

  protected def ttl(windowDuration: Duration): Int
}
