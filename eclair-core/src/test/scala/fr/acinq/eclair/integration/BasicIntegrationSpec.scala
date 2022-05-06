/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.integration

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.MilliSatoshiLong
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.channel._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 15/03/2017.
 */

class BasicIntegrationSpec extends IntegrationSpec {

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29800, "eclair.api.port" -> 28800, "eclair.channel.channel-flags.announce-channel" -> false).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.channel.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29801, "eclair.api.port" -> 28801).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))

    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29802, "eclair.api.port" -> 28802).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.channel.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29803, "eclair.api.port" -> 28803).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
  }

  test("connect nodes") {
    // A---B (private)
    // C---D (public)

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 11_000_000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 11_000_000 sat, 0 msat)

    val numberOfChannels = 2
    val channelEndpointsCount = 2 * numberOfChannels

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches = nodes.values.foldLeft(Set.empty[Watch[_]]) {
        case (watches, setup) =>
          setup.watcher ! ZmqWatcher.ListWatches(sender.ref)
          watches ++ sender.expectMsgType[Set[Watch[_]]]
      }
      watches.count(_.isInstanceOf[WatchFundingConfirmed]) == channelEndpointsCount
    }, max = 20 seconds, interval = 1 second)

    // confirming the funding tx
    generateBlocks(2)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](60 seconds).currentState == NORMAL) count = count + 1
      }
    }
  }

  test("wait for network announcements") {
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    generateBlocks(4)
    // A requires private channels, as a consequence:
    // - only A and B know about channel A-B (and there is no channel_announcement)
    awaitAnnouncements(nodes.view.filterKeys(key => List("A", "B").contains(key)).toMap, 0, 0, 0, 2)
    awaitAnnouncements(nodes.view.filterKeys(key => List("C", "D").contains(key)).toMap, 2, 1, 2, 0)
  }

}
