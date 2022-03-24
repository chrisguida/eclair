package fr.acinq.eclair.channel

import akka.actor.{ActorRef, FSM}
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, OutPoint, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchOutputSpent, WatchTxConfirmed}
import fr.acinq.eclair.channel.Channel.UnhandledExceptionStrategy
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx}
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.ClosingTx
import fr.acinq.eclair.wire.protocol.{AcceptChannel, Error, HtlcSettlementMessage, LightningMessage, OpenChannel, UpdateMessage}
import fr.acinq.eclair.{NodeParams, NotificationsLogger}

import java.sql.SQLException
import scala.concurrent.duration.DurationInt

trait ErrorHandlers extends FSM[ChannelState, ChannelStateData] {

  implicit def implicitLog: akka.event.DiagnosticLoggingAdapter

  def nodeParams: NodeParams

  def remoteNodeId: PublicKey

  def keyManager: ChannelKeyManager

  def txPublisher: akka.actor.typed.ActorRef[TxPublisher.Command]

  def blockchain: akka.actor.typed.ActorRef[ZmqWatcher.Command]

  def peer: akka.actor.ActorRef

  def activeConnection: akka.actor.ActorRef

  def self: akka.actor.ActorRef

  def handleFastClose(c: CloseCommand, channelId: ByteVector32) = {
    val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
    replyTo ! RES_SUCCESS(c, channelId)
    goto(CLOSED) using DATA_CLOSED(None)
  }

  def handleLocalError(cause: Throwable, msg: Option[Any]) = {
    cause match {
      case _: ForcedLocalCommit =>
        log.warning(s"force-closing channel at user request")
      case _ if msg.exists(_.isInstanceOf[OpenChannel]) || msg.exists(_.isInstanceOf[AcceptChannel]) =>
        // invalid remote channel parameters are logged as warning
        log.warning(s"${cause.getMessage} while processing msg=${msg.getOrElse("n/a").getClass.getSimpleName} in state=$stateName")
      case _: ChannelException =>
        log.error(s"${cause.getMessage} while processing msg=${msg.getOrElse("n/a").getClass.getSimpleName} in state=$stateName")
      case _ =>
        // unhandled error: we dump the channel data, and print the stack trace
        log.error(cause, s"msg=${msg.getOrElse("n/a")} stateData=$stateData:")
    }

    val error = Error(stateData.channelId, cause.getMessage)
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, LocalError(cause), isFatal = true))

    stateData.channelData() match {
      case Some(d) if Closing.nothingAtStake(d) => goto(CLOSED) using DATA_CLOSED(Some(d))
      case Some(negotiating@ChannelData.Negotiating(_, _, _, _, Some(bestUnpublishedClosingTx))) =>
        log.info(s"we have a valid closing tx, publishing it instead of our commitment: closingTxId=${bestUnpublishedClosingTx.tx.txid}")
        // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
        handleMutualClose(bestUnpublishedClosingTx, Left(negotiating))
      case Some(d) =>
        cause match {
          case _: ChannelException =>
            // known channel exception: we force close using our current commitment
            spendLocalCurrent(d) sending error
          case _ =>
            // unhandled exception: we apply the configured strategy
            nodeParams.channelConf.unhandledExceptionStrategy match {
              case UnhandledExceptionStrategy.LocalClose =>
                spendLocalCurrent(d) sending error
              case UnhandledExceptionStrategy.Stop =>
                log.error("unhandled exception: standard procedure would be to force-close the channel, but eclair has been configured to halt instead.")
                NotificationsLogger.logFatalError(
                  s"""stopping node as configured strategy to unhandled exceptions for nodeId=$remoteNodeId channelId=${d.channelId}
                     |
                     |Eclair has been configured to shut down when an unhandled exception happens, instead of requesting a
                     |force-close from the peer. This gives the operator a chance of avoiding an unnecessary mass force-close
                     |of channels that may be caused by a bug in Eclair, or issues like running out of disk space, etc.
                     |
                     |You should get in touch with Eclair developers and provide logs of your node for analysis.
                     |""".stripMargin, cause)
                sys.exit(1)
                stop(FSM.Shutdown)
            }
        }
      case None => goto(CLOSED) using DATA_CLOSED(None) sending error // when there is no commitment yet, we just send an error to our peer and go to CLOSED state
    }
  }

  def handleRemoteError(e: Error) = {
    // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
    log.error(s"peer sent error: ascii='${e.toAscii}' bin=${e.data.toHex}")
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, RemoteError(e), isFatal = true))

    stateData.channelData() match {
      case Some(_: ChannelData.Closing) => stay() // nothing to do, there is already a spending tx published
      case Some(negotiating@ChannelData.Negotiating(_, _, _, _, Some(bestUnpublishedClosingTx))) =>
        // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
        handleMutualClose(bestUnpublishedClosingTx, Left(negotiating))
      case Some(d: ChannelData.WaitingForFundingConfirmed) if Closing.nothingAtStake(d) => goto(CLOSED) using DATA_CLOSED(Some(d)) // the channel was never used and the funding tx may be double-spent
      case Some(d) => spendLocalCurrent(d) // NB: we publish the commitment even if we have nothing at stake (in a dataloss situation our peer will send us an error just for that)
      case None => goto(CLOSED) using DATA_CLOSED(None) // when there is no commitment yet, we just go to CLOSED state in case an error occurs
    }
  }

  def spendLocalCurrent(d: ChannelData) = {
    val outdatedCommitment = d match {
      case _: ChannelData.WaitingForRemotePublishFutureCommitment => true
      case closing: ChannelData.Closing if closing.futureRemoteCommitPublished.isDefined => true
      case _ => false
    }
    if (outdatedCommitment) {
      log.warning("we have an outdated commitment: will not publish our local tx")
      stay()
    } else {
      val commitTx = d.commitments.fullySignedLocalCommitTx(keyManager).tx
      val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, d.commitments, commitTx, nodeParams.currentBlockHeight, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
      val nextData = d match {
        case closing: ChannelData.Closing => closing.copy(localCommitPublished = Some(localCommitPublished))
        case negotiating: ChannelData.Negotiating => ChannelData.Closing(d.commitments, fundingTx = None, waitingSince = nodeParams.currentBlockHeight, negotiating.closingTxProposed.flatten.map(_.unsignedTx), localCommitPublished = Some(localCommitPublished))
        case waitForFundingConfirmed: ChannelData.WaitingForFundingConfirmed => ChannelData.Closing(d.commitments, fundingTx = waitForFundingConfirmed.fundingTx, waitingSince = nodeParams.currentBlockHeight, mutualCloseProposed = Nil, localCommitPublished = Some(localCommitPublished))
        case _ => ChannelData.Closing(d.commitments, fundingTx = None, waitingSince = nodeParams.currentBlockHeight, mutualCloseProposed = Nil, localCommitPublished = Some(localCommitPublished))
      }
      goto(CLOSING) using DATA_CLOSING(nextData) storing() calling doPublish(localCommitPublished, d.commitments)
    }
  }

  /**
   * This helper method will publish txs only if they haven't yet reached minDepth
   */
  private def publishIfNeeded(txs: Iterable[PublishTx], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    val (skip, process) = txs.partition(publishTx => Closing.inputAlreadySpent(publishTx.input, irrevocablySpent))
    process.foreach { publishTx => txPublisher ! publishTx }
    skip.foreach(publishTx => log.info("no need to republish tx spending {}:{}, it has already been confirmed", publishTx.input.txid, publishTx.input.index))
  }

  /**
   * This helper method will watch txs only if they haven't yet reached minDepth
   */
  private def watchConfirmedIfNeeded(txs: Iterable[Transaction], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    val (skip, process) = txs.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => blockchain ! WatchTxConfirmed(self, tx.txid, nodeParams.channelConf.minDepthBlocks))
    skip.foreach(tx => log.info(s"no need to watch txid=${tx.txid}, it has already been confirmed"))
  }

  /**
   * This helper method will watch txs only if the utxo they spend hasn't already been irrevocably spent
   *
   * @param parentTx transaction which outputs will be watched
   * @param outputs  outputs that will be watched. They must be a subset of the outputs of the `parentTx`
   */
  private def watchSpentIfNeeded(parentTx: Transaction, outputs: Iterable[OutPoint], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    outputs.foreach { output =>
      require(output.txid == parentTx.txid && output.index < parentTx.txOut.size, s"output doesn't belong to the given parentTx: output=${output.txid}:${output.index} (expected txid=${parentTx.txid} index < ${parentTx.txOut.size})")
    }
    val (skip, process) = outputs.partition(irrevocablySpent.contains)
    process.foreach(output => blockchain ! WatchOutputSpent(self, parentTx.txid, output.index.toInt, Set.empty))
    skip.foreach(output => log.info(s"no need to watch output=${output.txid}:${output.index}, it has already been spent by txid=${irrevocablySpent.get(output).map(_.txid)}"))
  }

  def doPublish(localCommitPublished: LocalCommitPublished, commitments: Commitments): Unit = {
    import localCommitPublished._

    val commitInput = commitments.commitInput.outPoint
    val isFunder = commitments.localParams.isFunder
    val publishQueue = commitments.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat =>
        val redeemableHtlcTxs = htlcTxs.values.flatten.map(tx => PublishFinalTx(tx, tx.fee, Some(commitTx.txid)))
        List(PublishFinalTx(commitTx, commitInput, "commit-tx", Closing.commitTxFee(commitments.commitInput, commitTx, isFunder), None)) ++ (claimMainDelayedOutputTx.map(tx => PublishFinalTx(tx, tx.fee, None)) ++ redeemableHtlcTxs ++ claimHtlcDelayedTxs.map(tx => PublishFinalTx(tx, tx.fee, None)))
      case _: Transactions.AnchorOutputsCommitmentFormat =>
        val redeemableHtlcTxs = htlcTxs.values.flatten.map(tx => PublishReplaceableTx(tx, commitments))
        val claimLocalAnchor = claimAnchorTxs.collect { case tx: Transactions.ClaimLocalAnchorOutputTx => PublishReplaceableTx(tx, commitments) }
        List(PublishFinalTx(commitTx, commitInput, "commit-tx", Closing.commitTxFee(commitments.commitInput, commitTx, isFunder), None)) ++ claimLocalAnchor ++ claimMainDelayedOutputTx.map(tx => PublishFinalTx(tx, tx.fee, None)) ++ redeemableHtlcTxs ++ claimHtlcDelayedTxs.map(tx => PublishFinalTx(tx, tx.fee, None))
    }
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txs' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainDelayedOutputTx.map(_.tx) ++ claimHtlcDelayedTxs.map(_.tx)
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    // we also watch our local anchor: this ensures that we will correctly detect when it's confirmed and count its fees
    // in the audit DB, even if we restart before confirmation
    val watchSpentQueue = htlcTxs.keys ++ claimAnchorTxs.collect { case tx: Transactions.ClaimLocalAnchorOutputTx => tx.input.outPoint }
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  def handleMutualClose(closingTx: ClosingTx, d: Either[ChannelData.Negotiating, ChannelData.Closing]) = {
    log.info(s"closing tx published: closingTxId=${closingTx.tx.txid}")
    val nextData = d match {
      case Left(negotiating) => ChannelData.Closing(negotiating.commitments, fundingTx = None, waitingSince = nodeParams.currentBlockHeight, negotiating.closingTxProposed.flatten.map(_.unsignedTx), mutualClosePublished = closingTx :: Nil)
      case Right(closing) => closing.copy(mutualClosePublished = closing.mutualClosePublished :+ closingTx)
    }
    goto(CLOSING) using DATA_CLOSING(nextData) storing() calling doPublish(closingTx, nextData.commitments.localParams.isFunder)
  }

  private def doPublish(closingTx: ClosingTx, isFunder: Boolean): Unit = {
    // the funder pays the fee
    val fee = if (isFunder) closingTx.fee else 0.sat
    txPublisher ! PublishFinalTx(closingTx, fee, None)
    blockchain ! WatchTxConfirmed(self, closingTx.tx.txid, nodeParams.channelConf.minDepthBlocks)
  }

  implicit def state2mystate(state: FSM.State[ChannelState, ChannelStateData]): MyState = MyState(state)

  case class MyState(state: FSM.State[ChannelState, ChannelStateData]) {

    def storing(unused: Unit = ()): FSM.State[ChannelState, ChannelStateData] = {
      state.stateData.channelData() match {
        case Some(d) =>
          log.debug("updating database record for channelId={}", d.channelId)
          nodeParams.db.channels.addOrUpdateChannel(d)
          context.system.eventStream.publish(ChannelPersisted(self, remoteNodeId, d.channelId, d))
          state
        case None =>
          log.error(s"can't store data=${state.stateData} in state=${state.stateName}")
          state
      }
    }

    def sending(msgs: Seq[LightningMessage]): FSM.State[ChannelState, ChannelStateData] = {
      msgs.foreach(sending)
      state
    }

    def sending(msg: LightningMessage): FSM.State[ChannelState, ChannelStateData] = {
      send(msg)
      state
    }

    /**
     * This method allows performing actions during the transition, e.g. after a call to [[MyState.storing]]. This is
     * particularly useful to publish transactions only after we are sure that the state has been persisted.
     */
    def calling(f: => Unit): FSM.State[ChannelState, ChannelStateData] = {
      f
      state
    }

    /**
     * We don't acknowledge htlc commands immediately, because we send them to the channel as soon as possible, and they
     * may not yet have been written to the database.
     *
     * @param cmd fail/fulfill command that has been processed
     */
    def acking(channelId: ByteVector32, cmd: HtlcSettlementCommand): FSM.State[ChannelState, ChannelStateData] = {
      log.debug("scheduling acknowledgement of cmd id={}", cmd.id)
      context.system.scheduler.scheduleOnce(10 seconds)(PendingCommandsDb.ackSettlementCommand(nodeParams.db.pendingCommands, channelId, cmd))(context.system.dispatcher)
      state
    }

    def acking(updates: List[UpdateMessage]): FSM.State[ChannelState, ChannelStateData] = {
      log.debug("scheduling acknowledgement of cmds ids={}", updates.collect { case s: HtlcSettlementMessage => s.id }.mkString(","))
      context.system.scheduler.scheduleOnce(10 seconds)(PendingCommandsDb.ackSettlementCommands(nodeParams.db.pendingCommands, updates))(context.system.dispatcher)
      state
    }

  }

  def send(msg: LightningMessage): Unit = {
    peer ! Peer.OutgoingMessage(msg, activeConnection)
  }

  /**
   * This helper function runs the state's default event handlers, and react to exceptions by unilaterally closing the channel
   */
  def handleExceptions(s: StateFunction): StateFunction = {
    case event if s.isDefinedAt(event) =>
      try {
        s(event)
      } catch {
        case t: SQLException =>
          log.error(t, "fatal database error\n")
          NotificationsLogger.logFatalError("eclair is shutting down because of a fatal database error", t)
          sys.exit(1)
        case t: Throwable => handleLocalError(t, None)
      }
  }

}
