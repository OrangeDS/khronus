package com.despegar.khronus.model

import com.despegar.khronus.util.Settings
import com.despegar.khronus.util.log.Logging

case class Tick(bucketNumber: BucketNumber) extends Logging {
  def startTimestamp = bucketNumber.startTimestamp()
  def endTimestamp = bucketNumber.endTimestamp()

  override def toString = s"Tick($bucketNumber)"
}

object Tick extends Logging {

  def current(): Tick = {
    val executionTimestamp = Timestamp(now - Settings.Window.ExecutionDelay)
    val bucketNumber = executionTimestamp.alignedTo(smallestWindow()).toBucketNumberOf(smallestWindow())
    val tick = Tick(bucketNumber - 1)
    log.debug(s"current $tick from executionTimestamp ${date(executionTimestamp.ms)}")
    tick
  }

  def now = System.currentTimeMillis()

  def smallestWindow() = Settings.Histogram.TimeWindows.head.duration

  def highestWindow() = Settings.Histogram.TimeWindows.last.duration
}