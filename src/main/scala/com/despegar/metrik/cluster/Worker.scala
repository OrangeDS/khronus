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

package com.despegar.metrik.cluster

import akka.actor.{ Props, Actor, ActorLogging }
import com.despegar.metrik.model.TimeWindowChain

import scala.util.{ Failure, Success }

class Worker extends Actor with ActorLogging with TimeWindowChainProvider {
  import context._

  def receive: Receive = idle

  def idle: Receive = {
    case Heartbeat ⇒
      sender ! Register(self)
      become(ready)
      log.info("Worker ready to work: [{}]", self.path)
  }

  def ready: Receive = {
    case Work(metric) ⇒
      log.info("Starting to process Metric: [{}]", metric)

      Thread.sleep(1000)
      sender() ! WorkDone(self)
//      timeWindowChain.process(metric) onComplete {
//        case Success(_)      ⇒ sender() ! WorkDone(self)
//        case Failure(reason) ⇒ throw reason
//      }

    case everythingElse ⇒ //ignore
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }
}

object Worker {
  def props: Props = Props(classOf[Worker])
}

trait TimeWindowChainProvider {
  def timeWindowChain: TimeWindowChain = new TimeWindowChain
}
