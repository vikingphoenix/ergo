package org.ergoplatform.nodeView

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.modifiers.history._
import org.ergoplatform.modifiers.mempool.proposition.AnyoneCanSpendProposition
import org.ergoplatform.modifiers.mempool.{AnyoneCanSpendTransaction, AnyoneCanSpendTransactionSerializer}
import org.ergoplatform.nodeView.history.{ErgoHistory, ErgoSyncInfo}
import org.ergoplatform.nodeView.mempool.ErgoMemPool
import org.ergoplatform.nodeView.state.{DigestState, ErgoState, UtxoState}
import org.ergoplatform.nodeView.wallet.ErgoWallet
import org.ergoplatform.settings.ErgoSettings
import scorex.core.ModifierTypeId
import scorex.core.serialization.Serializer
import scorex.core.transaction.Transaction
import scorex.core.{NodeViewHolder, NodeViewModifier}


abstract class ErgoNodeViewHolder[StateType <: ErgoState[StateType]](settings: ErgoSettings)
  extends NodeViewHolder[AnyoneCanSpendProposition.type, AnyoneCanSpendTransaction, ErgoPersistentModifier] {

  override lazy val networkChunkSize: Int = settings.scorexSettings.networkChunkSize

  override type MS = StateType
  override type SI = ErgoSyncInfo
  override type HIS = ErgoHistory
  override type VL = ErgoWallet
  override type MP = ErgoMemPool

  //todo: complete this
  override lazy val modifierCompanions: Map[ModifierTypeId, Serializer[_ <: NodeViewModifier]] =
  Map(Header.modifierTypeId -> HeaderSerializer,
    BlockTransactions.modifierTypeId -> BlockTransactionsSerializer,
    ADProofs.modifierTypeId -> ADProofSerializer,
    Transaction.ModifierTypeId -> AnyoneCanSpendTransactionSerializer)

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    reason.printStackTrace()
    System.exit(100) // this actor shouldn't be restarted at all so kill the whole app if that happened
  }
}

class UtxoErgoNodeViewHolder(settings: ErgoSettings) extends ErgoNodeViewHolder[UtxoState](settings) {

  /**
    * Hard-coded initial view all the honest nodes in a network are making progress from.
    */
  override protected def genesisState: (ErgoHistory, UtxoState, ErgoWallet, ErgoMemPool) = {
    val dir = new File(settings.directory)
    dir.mkdirs()

    val utxoState = ErgoState.generateGenesisUtxoState(dir)._1

    val history = ErgoHistory.readOrGenerate(settings)

    val wallet = ErgoWallet.readOrGenerate(settings)

    val memPool = ErgoMemPool.empty

    (history, utxoState, wallet, memPool)
  }

  /**
    * Restore a local view during a node startup. If no any stored view found
    * (e.g. if it is a first launch of a node) None is to be returned
    */
  override def restoreState(): Option[(ErgoHistory, UtxoState, ErgoWallet, ErgoMemPool)] = {
    ErgoState.readOrGenerate(settings).map {
      case us: UtxoState =>
        //todo: ensure that history is in certain mode
        val history = ErgoHistory.readOrGenerate(settings)

        val wallet = ErgoWallet.readOrGenerate(settings)

        val memPool = ErgoMemPool.empty

        (history, us, wallet, memPool)

      case _ => ??? //shouldn't be here
    }
  }
}


class DigestErgoNodeViewHolder(settings: ErgoSettings) extends ErgoNodeViewHolder[DigestState](settings) {

  /**
    * Hard-coded initial view all the honest nodes in a network are making progress from.
    */
  override protected def genesisState: (ErgoHistory, DigestState, ErgoWallet, ErgoMemPool) = {
    val dir = new File(settings.directory)
    dir.mkdirs()

    val digestState = ErgoState.generateGenesisDigestState(dir)

    //todo: ensure that history is in certain mode
    val history = ErgoHistory.readOrGenerate(settings)

    val wallet = ErgoWallet.readOrGenerate(settings)

    val memPool = ErgoMemPool.empty

    (history, digestState, wallet, memPool)
  }

  /**
    * Restore a local view during a node startup. If no any stored view found
    * (e.g. if it is a first launch of a node) None is to be returned
    */
  override def restoreState(): Option[(ErgoHistory, DigestState, ErgoWallet, ErgoMemPool)] = {
    ErgoState.readOrGenerate(settings).map {
      case ds: DigestState =>
        //todo: ensure that history is in certain mode
        val history = ErgoHistory.readOrGenerate(settings)

        val wallet = ErgoWallet.readOrGenerate(settings)

        val memPool = ErgoMemPool.empty

        (history, ds, wallet, memPool)
      case _ => ??? //shouldn't be here
    }
  }
}


object ErgoNodeViewHolder {
  def createActor(system: ActorSystem, settings: ErgoSettings): ActorRef = {
    if (settings.nodeSettings.ADState) system.actorOf(Props.create(classOf[DigestErgoNodeViewHolder], settings))
    else system.actorOf(Props.create(classOf[UtxoErgoNodeViewHolder], settings))
  }
}