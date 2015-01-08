package com.despegar.khronus.store

import com.despegar.khronus.model.BucketNumber._
import com.despegar.khronus.model.Timestamp._
import com.despegar.khronus.model.{HistogramBucket, Metric, Timestamp}
import com.despegar.khronus.util.{Settings, BaseIntegrationTest}
import org.HdrHistogram.Histogram
import org.scalatest.{FunSuite, Matchers}

import scala.concurrent.duration._
import scala.util.Random

class CassandraHistogramBucketStoreTest extends FunSuite with BaseIntegrationTest with Matchers {

  override val tableNames: Seq[String] = Settings.Window.WindowDurations.map(duration => Buckets.histogramBucketStore.tableName(duration))

  val testMetric = Metric("testMetric", "histogram")

  test("should store and retrieve buckets properly") {
    val histogram = HistogramBucket.newHistogram
    fill(histogram)
    val histogramBucket = new HistogramBucket((30, 30 seconds), histogram)
    await {
      Buckets.histogramBucketStore.store(testMetric, 30 seconds, Seq(histogramBucket))
    }

    val executionTimestamp: Timestamp = histogramBucket.bucketNumber.startTimestamp()
    val bucketTuplesFromCassandra = await {
      Buckets.histogramBucketStore.slice(testMetric, 1, executionTimestamp, 30 seconds)
    }
    val bucketTupleFromCassandra = bucketTuplesFromCassandra(0)

    histogram shouldEqual bucketTupleFromCassandra._2().histogram
  }

  test("should not retrieve buckets from the future") {
    val histogram = HistogramBucket.newHistogram
    val futureBucket = System.currentTimeMillis() + 60000 / (30 seconds).toMillis
    val bucketFromTheFuture = new HistogramBucket((futureBucket, 30 seconds), histogram)
    val bucketFromThePast = new HistogramBucket((30, 30 seconds), histogram)

    val buckets = Seq(bucketFromThePast, bucketFromTheFuture)

    await {
      Buckets.histogramBucketStore.store(testMetric, 30 seconds, buckets)
    }

    val bucketTuplesFromCassandra = await {
      Buckets.histogramBucketStore.slice(testMetric, 1, System.currentTimeMillis(), 30 seconds)
    }

    bucketTuplesFromCassandra should have length 1
    bucketTuplesFromCassandra(0)._2() shouldEqual bucketFromThePast
  }

  private def fill(histogram: Histogram) = {
    (1 to 10000) foreach { i => histogram.recordValue(Random.nextInt(200))}
  }

}
