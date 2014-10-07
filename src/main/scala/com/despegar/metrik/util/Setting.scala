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

package com.despegar.metrik.util

import java.util.concurrent.TimeUnit

import akka.actor._
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

class Settings(config: Config, extendedSystem: ExtendedActorSystem) extends Extension {

  object Master {
    val TickCronExpression = config.getString("metrik.master.tick-expression")
    val DiscoveryStartDelay = FiniteDuration(config.getDuration("metrik.master.discovery-start-delay", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
    val DiscoveryInterval = FiniteDuration(config.getDuration("metrik.master.discovery-interval", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  }

  object Http {
    val Interface = config.getString("metrik.endpoint")
    val Port: Int = config.getInt("metrik.port")
  }
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {
  override def lookup = Settings
  override def createExtension(system: ExtendedActorSystem) = new Settings(system.settings.config, system)

  object Metrik {
    val ActorSystem = "metrik-system"
  }
}

