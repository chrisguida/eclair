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

package fr.acinq.eclair.channel

import fr.acinq.eclair.FeatureSupport._
import fr.acinq.eclair.Features._
import fr.acinq.eclair.channel.states.ChannelStateTestsHelperMethods
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.{Feature, Features, InitFeature, PermanentOptionalChannelFeature, TestKitBaseClass}
import org.scalatest.funsuite.AnyFunSuiteLike

class ChannelFeaturesSpec extends TestKitBaseClass with AnyFunSuiteLike with ChannelStateTestsHelperMethods {

  test("channel features determines commitment format") {
    val standardChannel = ChannelFeatures()
    val staticRemoteKeyChannel = ChannelFeatures(Features.StaticRemoteKey)
    val anchorOutputsChannel = ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputs)
    val anchorOutputsZeroFeeHtlcsChannel = ChannelFeatures(Features.StaticRemoteKey, Features.AnchorOutputsZeroFeeHtlcTx)

    assert(!standardChannel.hasFeature(Features.StaticRemoteKey))
    assert(!standardChannel.hasFeature(Features.AnchorOutputs))
    assert(standardChannel.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(!standardChannel.paysDirectlyToWallet)

    assert(staticRemoteKeyChannel.hasFeature(Features.StaticRemoteKey))
    assert(!staticRemoteKeyChannel.hasFeature(Features.AnchorOutputs))
    assert(staticRemoteKeyChannel.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(staticRemoteKeyChannel.paysDirectlyToWallet)

    assert(anchorOutputsChannel.hasFeature(Features.StaticRemoteKey))
    assert(anchorOutputsChannel.hasFeature(Features.AnchorOutputs))
    assert(!anchorOutputsChannel.hasFeature(Features.AnchorOutputsZeroFeeHtlcTx))
    assert(anchorOutputsChannel.commitmentFormat === Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat)
    assert(!anchorOutputsChannel.paysDirectlyToWallet)

    assert(anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.StaticRemoteKey))
    assert(anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.AnchorOutputsZeroFeeHtlcTx))
    assert(!anchorOutputsZeroFeeHtlcsChannel.hasFeature(Features.AnchorOutputs))
    assert(anchorOutputsZeroFeeHtlcsChannel.commitmentFormat === Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    assert(!anchorOutputsZeroFeeHtlcsChannel.paysDirectlyToWallet)
  }

  test("pick channel type based on local and remote features") {
    case class TestCase(localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], expectedChannelType: ChannelType)
    val testCases = Seq(
      TestCase(Features.empty, Features.empty, ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional), Features.empty, ChannelTypes.Standard),
      TestCase(Features.empty, Features(StaticRemoteKey -> Optional), ChannelTypes.Standard),
      TestCase(Features.empty, Features(StaticRemoteKey -> Mandatory), ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional, Wumbo -> Mandatory), Features(Wumbo -> Mandatory), ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional), Features(StaticRemoteKey -> Optional), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional), Features(StaticRemoteKey -> Mandatory), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional, Wumbo -> Optional), Features(StaticRemoteKey -> Mandatory, Wumbo -> Mandatory), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), ChannelTypes.AnchorOutputs),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), ChannelTypes.AnchorOutputs),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), ChannelTypes.AnchorOutputsZeroFeeHtlcTx),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), ChannelTypes.AnchorOutputsZeroFeeHtlcTx),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Mandatory), ChannelTypes.AnchorOutputsZeroFeeHtlcTx),
    )

    for (testCase <- testCases) {
      assert(ChannelTypes.defaultFromFeatures(testCase.localFeatures, testCase.remoteFeatures) === testCase.expectedChannelType, s"localFeatures=${testCase.localFeatures} remoteFeatures=${testCase.remoteFeatures}")
    }
  }

  test("create channel type from features") {
    case class TestCase(features: Features[InitFeature], expectedChannelType: ChannelType)

    val validChannelTypes = Seq(
      TestCase(Features.empty, ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Mandatory), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory), ChannelTypes.AnchorOutputs),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Mandatory), ChannelTypes.AnchorOutputsZeroFeeHtlcTx),
    )
    for (testCase <- validChannelTypes) {
      assert(ChannelTypes.fromFeatures(testCase.features) === testCase.expectedChannelType, testCase.features)
    }

    val invalidChannelTypes: Seq[Features[InitFeature]] = Seq(
      Features(Wumbo -> Optional),
      Features(StaticRemoteKey -> Optional),
      Features(StaticRemoteKey -> Mandatory, Wumbo -> Optional),
      Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional),
      Features(StaticRemoteKey -> Optional, AnchorOutputs -> Mandatory),
      Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional),
      Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Mandatory),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Optional),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory, AnchorOutputsZeroFeeHtlcTx -> Mandatory),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Mandatory),
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory, Wumbo -> Optional),
    )
    for (features <- invalidChannelTypes) {
      assert(ChannelTypes.fromFeatures(features) === ChannelTypes.UnsupportedChannelType(features), features)
    }
  }

  test("enrich channel type with optional permanent channel features") {
    case class TestCase(channelType: SupportedChannelType, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], expected: Set[Feature])
    val testCases = Seq(
      TestCase(ChannelTypes.Standard, Features(Wumbo -> Optional), Features.empty, Set.empty),
      TestCase(ChannelTypes.Standard, Features(Wumbo -> Optional), Features(Wumbo -> Optional), Set(Wumbo)),
      TestCase(ChannelTypes.Standard, Features(Wumbo -> Mandatory), Features(Wumbo -> Optional), Set(Wumbo)),
      TestCase(ChannelTypes.StaticRemoteKey, Features(Wumbo -> Optional), Features.empty, Set(StaticRemoteKey)),
      TestCase(ChannelTypes.StaticRemoteKey, Features(Wumbo -> Optional), Features(Wumbo -> Optional), Set(StaticRemoteKey, Wumbo)),
      TestCase(ChannelTypes.AnchorOutputs, Features.empty, Features(Wumbo -> Optional), Set(StaticRemoteKey, AnchorOutputs)),
      TestCase(ChannelTypes.AnchorOutputs, Features(Wumbo -> Optional), Features(Wumbo -> Mandatory), Set(StaticRemoteKey, AnchorOutputs, Wumbo)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx, Features.empty, Features(Wumbo -> Optional), Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx, Features(Wumbo -> Optional), Features(Wumbo -> Mandatory), Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, Wumbo)),
      TestCase(ChannelTypes.AnchorOutputsZeroFeeHtlcTx, Features(DualFunding -> Optional, Wumbo -> Optional), Features(DualFunding -> Optional, Wumbo -> Optional), Set(StaticRemoteKey, AnchorOutputsZeroFeeHtlcTx, Wumbo, DualFunding)),
    )
    testCases.foreach(t => assert(ChannelFeatures(t.channelType, t.localFeatures, t.remoteFeatures).features === t.expected, s"channelType=${t.channelType} localFeatures=${t.localFeatures} remoteFeatures=${t.remoteFeatures}"))
  }

  test("channel types and optional permanent channel features don't overlap") {
    import scala.reflect.ClassTag
    import scala.reflect.runtime.universe._
    import scala.reflect.runtime.{universe => runtime}
    val mirror = runtime.runtimeMirror(ClassLoader.getSystemClassLoader)

    def extract[T: TypeTag](container: T)(implicit c: ClassTag[T]): Set[SupportedChannelType] = {
      typeOf[T].decls.filter(_.isPublic).flatMap(symbol => {
        if (symbol.isTerm && symbol.isModule) {
          mirror.reflectModule(symbol.asModule).instance match {
            case f: SupportedChannelType => Some(f)
            case _ => None
          }
        } else {
          None
        }
      }).toSet
    }

    val channelTypes = extract(ChannelTypes)
    assert(channelTypes.nonEmpty)
    val channelTypeFeatures = channelTypes.flatMap(_.features)
    channelTypeFeatures.foreach(f => assert(!f.isInstanceOf[PermanentOptionalChannelFeature]))
  }

}
