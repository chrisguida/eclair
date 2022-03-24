package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM, Status}
import akka.pattern.pipe
import fr.acinq.bitcoin.Script
import fr.acinq.eclair.{Features, toLongId}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.channel.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelTlv, Error, FundingCreated, OpenChannel, TlvStream}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext

trait ChannelOpenSingle extends FSM[ChannelState, ChannelStateData] with ErrorHandlers {

  implicit def ec: ExecutionContext

  def wallet: OnChainChannelFunder

  def origin_opt: Option[ActorRef]

  def waitForOpenChannel: StateFunction = {
    case Event(open: OpenChannel, DATA_WAIT_FOR_OPEN_CHANNEL(INPUT_INIT_FUNDEE(_, localParams, _, remoteInit, channelConfig, channelType))) =>
      Helpers.validateParamsFundee(nodeParams, channelType, localParams.initFeatures, open, remoteNodeId, remoteInit.features) match {
        case Left(t) => handleLocalError(t, Some(open))
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isFunder = false, open.temporaryChannelId, open.feeratePerKw, None))
          val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
          val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
          val minimumDepth = Helpers.minDepthForFunding(nodeParams.channelConf, open.fundingSatoshis)
          // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used.
          // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
          val localShutdownScript = if (Features.canUseFeature(localParams.initFeatures, remoteInit.features, Features.UpfrontShutdownScript)) localParams.defaultFinalScriptPubKey else ByteVector.empty
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = localParams.dustLimit,
            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = localParams.channelReserve,
            minimumDepth = minimumDepth,
            htlcMinimumMsat = localParams.htlcMinimum,
            toSelfDelay = localParams.toSelfDelay,
            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
            fundingPubkey = fundingPubkey,
            revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
            paymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
            htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
            firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
            tlvStream = TlvStream(
              ChannelTlv.UpfrontShutdownScriptTlv(localShutdownScript),
              ChannelTlv.ChannelTypeTlv(channelType)
            ))
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = open.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
            channelReserve = open.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimum = open.htlcMinimumMsat,
            toSelfDelay = open.toSelfDelay,
            maxAcceptedHtlcs = open.maxAcceptedHtlcs,
            fundingPubKey = open.fundingPubkey,
            revocationBasepoint = open.revocationBasepoint,
            paymentBasepoint = open.paymentBasepoint,
            delayedPaymentBasepoint = open.delayedPaymentBasepoint,
            htlcBasepoint = open.htlcBasepoint,
            initFeatures = remoteInit.features,
            shutdownScript = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, localParams, remoteParams, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, open.firstPerCommitmentPoint, open.channelFlags, channelConfig, channelFeatures, accept) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, _: DATA_WAIT_FOR_OPEN_CHANNEL) => handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED) using DATA_CLOSED(None)

  }

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, DATA_WAIT_FOR_OPEN_CHANNEL(INPUT_INIT_FUNDEE(_, localParams, _, remoteInit, channelConfig, channelType))) =>
      Helpers.validateParamsFundee(nodeParams, channelType, localParams.initFeatures, open, remoteNodeId, remoteInit.features) match {
        case Left(t) => handleLocalError(t, Some(open))
        case Right((channelFeatures, remoteShutdownScript)) =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isFunder = false, open.temporaryChannelId, open.feeratePerKw, None))
          val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
          val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
          val minimumDepth = Helpers.minDepthForFunding(nodeParams.channelConf, open.fundingSatoshis)
          // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script if this feature is not used.
          // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
          val localShutdownScript = if (Features.canUseFeature(localParams.initFeatures, remoteInit.features, Features.UpfrontShutdownScript)) localParams.defaultFinalScriptPubKey else ByteVector.empty
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = localParams.dustLimit,
            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = localParams.channelReserve,
            minimumDepth = minimumDepth,
            htlcMinimumMsat = localParams.htlcMinimum,
            toSelfDelay = localParams.toSelfDelay,
            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
            fundingPubkey = fundingPubkey,
            revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
            paymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
            htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
            firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
            tlvStream = TlvStream(
              ChannelTlv.UpfrontShutdownScriptTlv(localShutdownScript),
              ChannelTlv.ChannelTypeTlv(channelType)
            ))
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = open.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
            channelReserve = open.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimum = open.htlcMinimumMsat,
            toSelfDelay = open.toSelfDelay,
            maxAcceptedHtlcs = open.maxAcceptedHtlcs,
            fundingPubKey = open.fundingPubkey,
            revocationBasepoint = open.revocationBasepoint,
            paymentBasepoint = open.paymentBasepoint,
            delayedPaymentBasepoint = open.delayedPaymentBasepoint,
            htlcBasepoint = open.htlcBasepoint,
            initFeatures = remoteInit.features,
            shutdownScript = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, localParams, remoteParams, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, open.firstPerCommitmentPoint, open.channelFlags, channelConfig, channelFeatures, accept) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, _: DATA_WAIT_FOR_OPEN_CHANNEL) => handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED) using DATA_CLOSED(None)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, DATA_WAIT_FOR_ACCEPT_CHANNEL(INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTxFeeratePerKw, localParams, _, remoteInit, _, channelConfig, channelType), open)) =>
      Helpers.validateParamsFunder(nodeParams, channelType, localParams.initFeatures, remoteInit.features, open, accept) match {
        case Left(t) =>
          channelOpenReplyToUser(Left(LocalError(t)))
          handleLocalError(t, Some(accept))
        case Right((channelFeatures, remoteShutdownScript)) =>
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = accept.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
            channelReserve = accept.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimum = accept.htlcMinimumMsat,
            toSelfDelay = accept.toSelfDelay,
            maxAcceptedHtlcs = accept.maxAcceptedHtlcs,
            fundingPubKey = accept.fundingPubkey,
            revocationBasepoint = accept.revocationBasepoint,
            paymentBasepoint = accept.paymentBasepoint,
            delayedPaymentBasepoint = accept.delayedPaymentBasepoint,
            htlcBasepoint = accept.htlcBasepoint,
            initFeatures = remoteInit.features,
            shutdownScript = remoteShutdownScript)
          log.debug("remote params: {}", remoteParams)
          val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey)))
          wallet.makeFundingTx(fundingPubkeyScript, fundingSatoshis, fundingTxFeeratePerKw).pipeTo(self)
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, accept.firstPerCommitmentPoint, channelConfig, channelFeatures, open)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.lastSent.temporaryChannelId)))
      handleFastClose(c, d.lastSent.temporaryChannelId)

    case Event(e: Error, _: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED) using DATA_CLOSED(None)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED) using DATA_CLOSED(None)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, remoteFirstPerCommitmentPoint, channelConfig, channelFeatures, open)) =>
      // let's create the first commitment tx that spends the yet uncommitted funding tx
      Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, None)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          require(fundingTx.txOut(fundingTxOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, s"pubkey script mismatch!")
          val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath), TxOwner.Remote, channelFeatures.commitmentFormat)
          // signature of their initial commitment tx that pays remote pushMsat
          val fundingCreated = FundingCreated(
            temporaryChannelId = temporaryChannelId,
            fundingTxid = fundingTx.hash,
            fundingOutputIndex = fundingTxOutputIndex,
            signature = localSigOfRemoteTx
          )
          val channelId = toLongId(fundingTx.hash, fundingTxOutputIndex)
          peer ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
          // NB: we don't send a ChannelSignatureSent for the first commit
          goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, fundingTxFee, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint), open.channelFlags, channelConfig, channelFeatures, fundingCreated) sending fundingCreated
      }

    case Event(Status.Failure(t), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      log.error(t, s"wallet returned error: ")
      channelOpenReplyToUser(Left(LocalError(t)))
      handleLocalError(ChannelFundingError(d.temporaryChannelId), None) // we use a generic exception and don't send the internal error to the peer

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.temporaryChannelId)))
      handleFastClose(c, d.temporaryChannelId)

    case Event(e: Error, _: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED) using DATA_CLOSED(None)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED) using DATA_CLOSED(None)
  })

  /**
   * This function is used to return feedback to user at channel opening
   */
  def channelOpenReplyToUser(message: Either[ChannelOpenError, ChannelOpenResponse]): Unit = {
    val m = message match {
      case Left(LocalError(t)) => Status.Failure(t)
      case Left(RemoteError(e)) => Status.Failure(new RuntimeException(s"peer sent error: ascii='${e.toAscii}' bin=${e.data.toHex}"))
      case Right(s) => s
    }
    origin_opt.foreach(_ ! m)
  }

}
