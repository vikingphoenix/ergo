package org.ergoplatform.local

import akka.actor.{Actor, ActorRef, ActorRefFactory, PoisonPill, Props}
import io.circe.Encoder
import io.circe.syntax._
import io.iohk.iodb.ByteArrayWrapper
import org.ergoplatform.mining.CandidateBlock
import org.ergoplatform.mining.difficulty.RequiredDifficulty
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.Header
import org.ergoplatform.modifiers.mempool.AnyoneCanSpendTransaction
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.nodeView.state.UtxoState
import org.ergoplatform.nodeView.wallet.ErgoWallet
import org.ergoplatform.settings.{Algos, Constants, ErgoSettings}
import scorex.core.NodeViewHolder
import scorex.core.NodeViewHolder.ReceivableMessages._
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.SemanticallySuccessfulModifier
import scorex.core.utils.{NetworkTimeProvider, ScorexLogging}

import scala.collection._
import scala.collection.mutable.ArrayBuffer

class ErgoMiner(ergoSettings: ErgoSettings,
                viewHolderRef: ActorRef,
                readersHolderRef: ActorRef,
                nodeId: Array[Byte],
                timeProvider: NetworkTimeProvider) extends Actor with ScorexLogging {

  import ErgoMiner._

  private val startTime = timeProvider.time()
  private val votes: Array[Byte] = nodeId

  //shared mutable state
  private var isMining = false
  private var candidateOpt: Option[CandidateBlock] = None
  private val miningThreads: mutable.Buffer[ActorRef] = new ArrayBuffer[ActorRef]()

  override def preStart(): Unit = {
    viewHolderRef ! Subscribe(Seq(NodeViewHolder.EventType.SuccessfulSemanticallyValidModifier))
  }

  override def postStop(): Unit = killAllThreads

  private def killAllThreads: Unit = {
    log.warn("Stopping miner's threads.")
    miningThreads.foreach( _ ! PoisonPill)
    miningThreads.clear()
  }

  private def unknownMessage: Receive = {
    case m =>
      log.warn(s"Unexpected message $m")
  }

  private def miningStatus: Receive = {
    case MiningStatusRequest =>
      sender ! MiningStatusResponse(isMining, votes, candidateOpt)
  }

  private def startMining: Receive = {
    case StartMining if candidateOpt.nonEmpty && !isMining && ergoSettings.nodeSettings.mining =>
      log.info("Starting Mining")
      isMining = true
      miningThreads += ErgoMiningThread(ergoSettings, viewHolderRef, candidateOpt.get)(context)
      miningThreads.foreach(_ ! candidateOpt.get)
    case StartMining if candidateOpt.isEmpty =>
      produceCandidate
  }

  private def needNewCandidate(b: ErgoFullBlock): Boolean = {
    val parentHeaderIdOpt = candidateOpt.flatMap(_.parentOpt).map(_.id)
    !parentHeaderIdOpt.exists(_.sameElements(b.header.id))
  }

  private def shouldStartMine(b: ErgoFullBlock): Boolean = {
    ergoSettings.nodeSettings.mining && b.header.timestamp >= startTime
  }

  private def receiveSemanticallySuccessfulModifier: Receive = {
    /**
      * Case when we are already mining by the time modifier arrives and
      * get block from node view that has header's id which isn't equals to our candidate's parent id.
      * That means that our candidate is outdated. Should produce new candidate for ourselves.
      * Stop all current threads and re-run them with newly produced candidate.
      */
    case SemanticallySuccessfulModifier(mod: ErgoFullBlock) if isMining && needNewCandidate(mod) => produceCandidate
    /**
      * Non obvious but case when mining is enabled, but miner doesn't started yet. Initialization case.
      * We've received block that been generated by somebody else or genesis while we doesn't start.
      * And this block was generated after our miner had been started. That means that we are ready
      * to start mining.
      * This block could be either genesis or generated by another node.
      */
    case SemanticallySuccessfulModifier(mod: ErgoFullBlock) if shouldStartMine(mod)  => self ! StartMining
    /**
      * Just ignore all other modifiers.
      */
    case SemanticallySuccessfulModifier =>
  }

  private def receiverCandidateBlock: Receive = {
    case c: CandidateBlock =>
      procCandidateBlock(c)
    case cEnv: CandidateEnvelope if cEnv.c.nonEmpty =>
      procCandidateBlock(cEnv.c.get)
  }

  override def receive: Receive = receiveSemanticallySuccessfulModifier orElse
    receiverCandidateBlock orElse
    miningStatus orElse
    startMining orElse
    unknownMessage

  private def procCandidateBlock(c: CandidateBlock): Unit = {
    log.debug(s"Got candidate block $c")
    candidateOpt = Some(c)
    if (!isMining) self ! StartMining
    miningThreads.foreach(_ ! c)
  }

  private def createCoinbase(state: UtxoState, height: Int): AnyoneCanSpendTransaction = {
    val txBoxes = state.anyoneCanSpendBoxesAtHeight(height)
    AnyoneCanSpendTransaction(txBoxes.map(_.nonce), txBoxes.map(_.value))
  }

  private def createCandidate(history: ErgoHistory,
                              pool: ErgoMemPool,
                              state: UtxoState,
                              bestHeaderOpt: Option[Header]): CandidateBlock = {
    val height = bestHeaderOpt.map(_.height + 1).getOrElse(0)
    val coinbase = createCoinbase(state, height)

    //only transactions valid from against the current utxo state we take from the mem pool
    //todo: move magic number to testnet settings
    val txs = coinbase +: state.filterValid(pool.unconfirmed.values.toSeq)

    //we also filter transactions which are trying to spend the same box. Currently, we pick just the first one
    //of conflicting transaction. Another strategy is possible(e.g. transaction with highest fee)
    //todo: move this logic to MemPool.put? Problem we have now is that conflicting transactions are still in
    // the pool
    val txsNoConflict = fixTxsConflicts(txs)

    val (adProof, adDigest) = state.proofsForTransactions(txsNoConflict).get

    val timestamp = timeProvider.time()

    val nBits: Long = bestHeaderOpt
      .map(parent => history.requiredDifficultyAfter(parent))
      .map(d => RequiredDifficulty.encodeCompactBits(d))
      .getOrElse(Constants.InitialNBits)

    val candidate = CandidateBlock(bestHeaderOpt, nBits, adDigest, adProof, txsNoConflict, timestamp, nodeId)
    log.debug(s"Send candidate block with ${candidate.transactions.size} transactions")
    //TODO takes a lot of time
    candidate
  }

  def produceCandidate: Unit =
    viewHolderRef ! GetDataFromCurrentView[ErgoHistory, UtxoState, ErgoWallet, ErgoMemPool, CandidateEnvelope] { v =>
      log.info("Start candidate creation")
      val history = v.history
      val state = v.state
      val pool = v.pool
      val bestHeaderOpt = history.bestFullBlockOpt.map(_.header)

      if (bestHeaderOpt.isDefined || ergoSettings.nodeSettings.offlineGeneration) {
        val candidate = createCandidate(history, pool, state, bestHeaderOpt)
        CandidateEnvelope.fromCandidate(candidate)
      } else {
        CandidateEnvelope.empty
      }
    }

  private def fixTxsConflicts(txs: Seq[AnyoneCanSpendTransaction]): Seq[AnyoneCanSpendTransaction] = txs
    .foldLeft((Seq.empty[AnyoneCanSpendTransaction], Set.empty[ByteArrayWrapper])) { case ((s, keys), tx) =>
      val bxsBaw = tx.boxIdsToOpen.map(ByteArrayWrapper.apply)
      if (bxsBaw.forall(k => !keys.contains(k)) && bxsBaw.size == bxsBaw.toSet.size) {
        (s :+ tx) -> (keys ++ bxsBaw)
      } else {
        (s, keys)
      }
    }._1
}


object ErgoMiner extends ScorexLogging {


  case object StartMining

  case object MiningStatusRequest

  case class MiningStatusResponse(isMining: Boolean, votes: Array[Byte], candidateBlock: Option[CandidateBlock])

  case class CandidateEnvelope(c: Option[CandidateBlock])

  object CandidateEnvelope {
    val empty = CandidateEnvelope(None)

    def fromCandidate(c: CandidateBlock): CandidateEnvelope = CandidateEnvelope(Some(c))
  }

  implicit val jsonEncoder: Encoder[MiningStatusResponse] = (r: MiningStatusResponse) =>
    Map(
      "isMining" -> r.isMining.asJson,
      "votes" -> Algos.encode(r.votes).asJson,
      "candidateBlock" -> r.candidateBlock.map(_.asJson).getOrElse("None".asJson)
    ).asJson

}

object ErgoMinerRef {

  def props(ergoSettings: ErgoSettings,
            viewHolderRef: ActorRef,
            readersHolderRef: ActorRef,
            nodeId: Array[Byte],
            timeProvider: NetworkTimeProvider): Props =
    Props(new ErgoMiner(ergoSettings, viewHolderRef, readersHolderRef, nodeId, timeProvider))

  def apply(ergoSettings: ErgoSettings,
            viewHolderRef: ActorRef,
            readersHolderRef: ActorRef,
            nodeId: Array[Byte],
            timeProvider: NetworkTimeProvider)
           (implicit context: ActorRefFactory): ActorRef =
    context.actorOf(props(ergoSettings, viewHolderRef, readersHolderRef, nodeId, timeProvider))

  def apply(ergoSettings: ErgoSettings,
            viewHolderRef: ActorRef,
            readersHolderRef: ActorRef,
            nodeId: Array[Byte],
            timeProvider: NetworkTimeProvider,
            name: String)
           (implicit context: ActorRefFactory): ActorRef =
    context.actorOf(props(ergoSettings, viewHolderRef, readersHolderRef, nodeId, timeProvider), name)
}
