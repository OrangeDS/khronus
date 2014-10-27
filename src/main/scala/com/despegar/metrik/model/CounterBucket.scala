package com.despegar.metrik.model

import scala.concurrent.duration.Duration

class CounterBucket(override val bucketNumber: Long, override val duration: Duration, val counts: Long) extends Bucket(bucketNumber, duration) {

  override def summary = CounterSummary(timestamp, counts)

}

object CounterBucket {
  implicit def sumCounters(buckets: Seq[CounterBucket]): Long = buckets.map(_.counts).sum
}
