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

package com.despegar.metrik.model

import java.util.concurrent.TimeUnit
import com.despegar.metrik.store.MetaSupport
import com.despegar.metrik.util.{ BucketUtils, Logging, Settings }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._ //remove this

class TimeWindowChain extends Logging with MetaSupport {

  val windows = Seq(TimeWindow(30 seconds, 1 millis), TimeWindow(1 minute, 30 seconds), TimeWindow(5 minute, 1 minute, false))

  def process(metric: String): Future[Seq[Any]] = {
    val executionTimestamp = System.currentTimeMillis() - Settings().Window.ExecutionDelay
    log.debug(s"Processing windows for $metric...")
    Future.sequence(windows.map(_.process(metric, executionTimestamp))).andThen {
      //update the execution timestamp, aligned to the first window
      case _ ⇒ metaStore.update(metric, BucketUtils.getCurrentBucketTimestamp(windows(0).duration, executionTimestamp))
    }
  }

}