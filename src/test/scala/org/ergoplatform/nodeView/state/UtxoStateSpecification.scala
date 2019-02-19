package org.ergoplatform.nodeView.state

import java.util.concurrent.Executors

import io.iohk.iodb.ByteArrayWrapper
import org.ergoplatform.ErgoBox.{R4, TokenId}
import org.ergoplatform._
import org.ergoplatform.mining._
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.{ADProofs, BlockTransactions, Extension, Header}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnsignedErgoTransaction}
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.LaunchParameters
import org.ergoplatform.utils.ErgoPropertyTest
import org.ergoplatform.utils.generators.ErgoTransactionGenerators
import scorex.core._
import scorex.crypto.hash.Digest32
import scorex.util.encode.Base16
import sigmastate.Values
import sigmastate.Values.ByteArrayConstant
import sigmastate.basics.DLogProtocol.{DLogProverInput, ProveDlog}
import sigmastate.eval.CompiletimeIRContext
import sigmastate.serialization.ValueSerializer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Random, Try}


class UtxoStateSpecification extends ErgoPropertyTest with ErgoTransactionGenerators {

  property("Founders box workflow") {
    var (us, bh) = createUtxoState()
    val foundersBox = genesisBoxes.last
    val genesis = validFullBlock(parentOpt = None, us, bh)
    us = us.applyModifier(genesis).get

    // spent founders box, changing custom part of proposition to tokenThreshold
    val tokenId: TokenId = Digest32 !@@ foundersBox.id
    val tx = {
      val inputs = IndexedSeq(Input(foundersBox.id, emptyProverResult))
      val newProp = ErgoScriptPredef.tokenThresholdScript(tokenId, 50, ErgoAddressEncoder.MainnetNetworkPrefix)(new CompiletimeIRContext)
      val height = genesis.header.height + 1
      val remaining = emission.remainingFoundationRewardAtHeight(genesis.header.height)
      val minAmount = LaunchParameters.minValuePerByte * 1000
      val newBoxes = IndexedSeq(
        ErgoBox(remaining, foundersBox.proposition, height, Seq(), Map(R4 -> ByteArrayConstant(ValueSerializer.serialize(newProp)))),
        ErgoBox(minAmount, defaultProver.secrets.head.publicImage, height, Seq((tokenId, 49L))),
        ErgoBox(foundersBox.value - remaining - minAmount, defaultProver.secrets.last.publicImage, height, Seq((tokenId, 49L)))
      )
      val unsignedTx = new UnsignedErgoTransaction(inputs, newBoxes)
      defaultProver.sign(unsignedTx, IndexedSeq(foundersBox), us.stateContext).get
    }
    val block1 = validFullBlock(Some(genesis.header), us, Seq(tx))
    us = us.applyModifier(block1).get

    // spent founders box with tokenThreshold
    val tx2 = {
      val foundersBox = tx.outputs.head
      val inputs = tx.outputs.map(b => Input(b.id, emptyProverResult))
      val height = block1.header.height + 1
      val remaining = emission.remainingFoundationRewardAtHeight(block1.header.height)
      val inputValue = tx.outputs.map(_.value).sum
      val newBoxes = IndexedSeq(
        ErgoBox(remaining, foundersBox.proposition, height, Seq(), foundersBox.additionalRegisters),
        ErgoBox(inputValue - remaining, defaultProver.secrets.last.publicImage, height, Seq((tokenId, 98L)))
      )
      val unsignedTx = new UnsignedErgoTransaction(inputs, newBoxes)
      defaultProver.sign(unsignedTx, tx.outputs, us.stateContext).get
    }
    val block2 = validFullBlock(Some(block1.header), us, Seq(tx2))
    us = us.applyModifier(block2).get
  }

  property("Founders should be able to spend genesis founders box") {
    var (us, bh) = createUtxoState()
    val foundersBox = genesisBoxes.last
    var height: Int = ErgoHistory.GenesisHeight

    val settingsPks = settings.chainSettings.foundersPubkeys
      .map(str => groupElemFromBytes(Base16.decode(str).get))
      .map(pk => ProveDlog(pk))
    settingsPks.count(p => defaultProver.dlogPubkeys.contains(p)) shouldBe 2

    forAll(defaultHeaderGen) { header =>
      val rewardPk = new DLogProverInput(BigInt(header.height).bigInteger).publicImage

      val t = validTransactionsFromBoxHolder(bh, new Random(height))
      val txs = t._1
      bh = t._2
      val (adProofBytes, adDigest) = us.proofsForTransactions(txs).get
      val realHeader = header.copy(stateRoot = adDigest, ADProofsRoot = ADProofs.proofDigest(adProofBytes), height = height)
      val adProofs = ADProofs(realHeader.id, adProofBytes)
      val fb = ErgoFullBlock(realHeader, BlockTransactions(realHeader.id, txs), Extension(realHeader), Some(adProofs))
      us = us.applyModifier(fb).get
      val remaining = emission.remainingFoundationRewardAtHeight(height)

      // check validity of transaction, spending founders box
      val inputs = IndexedSeq(Input(foundersBox.id, emptyProverResult))
      val newBoxes = IndexedSeq(
        ErgoBox(remaining, foundersBox.proposition, height, Seq(), foundersBox.additionalRegisters),
        ErgoBox(foundersBox.value - remaining, rewardPk, height, Seq())
      )
      val unsignedTx = new UnsignedErgoTransaction(inputs, newBoxes)
      val tx = defaultProver.sign(unsignedTx, IndexedSeq(foundersBox), us.stateContext).get
      us.validate(tx) shouldBe 'success
      height = height + 1
    }
  }

  property("Correct genesis state") {
    val (us, bh) = createUtxoState()
    val boxes = bh.boxes.values.toList
    boxes.size shouldBe 3

    // check tests consistency
    genesisBoxes.length shouldBe bh.boxes.size
    genesisBoxes.foreach { b =>
      us.boxById(b.id).isDefined shouldBe true
      bh.boxes.get(ByteArrayWrapper(b.id)).isDefined shouldBe true
    }

    // check total supply
    boxes.map(_.value).sum shouldBe coinsTotal

    // boxes should contain all no-premine proofs in registers
    val additionalRegisters = boxes.flatMap(_.additionalRegisters.values)
    initSettings.chainSettings.noPremineProof.foreach { pStr =>
      val pBytes = ByteArrayConstant(pStr.getBytes("UTF-8"))
      additionalRegisters should contain(pBytes)
    }

  }

  property("extractEmissionBox() should extract correct box") {
    var (us, bh) = createUtxoState()
    us.emissionBoxOpt should not be None
    var lastBlockOpt: Option[Header] = None
    forAll { seed: Int =>
      val blBh = validFullBlockWithBoxHolder(lastBlockOpt, us, bh, new Random(seed))
      val block = blBh._1
      us.extractEmissionBox(block) should not be None
      lastBlockOpt = Some(block.header)
      bh = blBh._2
      us = us.applyModifier(block).get
    }
  }

  property("fromBoxHolder") {
    forAll(boxesHolderGen) { bh =>
      val us = createUtxoState(bh)
      bh.take(1000)._1.foreach { box =>
        us.boxById(box.id) shouldBe Some(box)
      }
    }
  }

  property("proofsForTransactions") {
    var (us: UtxoState, bh) = createUtxoState()
    var height: Int = ErgoHistory.GenesisHeight
    forAll(defaultHeaderGen) { header =>
      val t = validTransactionsFromBoxHolder(bh, new Random(height))
      val txs = t._1
      bh = t._2
      val (adProofBytes, adDigest) = us.proofsForTransactions(txs).get
      val realHeader = header.copy(stateRoot = adDigest, ADProofsRoot = ADProofs.proofDigest(adProofBytes), height = height)
      val adProofs = ADProofs(realHeader.id, adProofBytes)
      val fb = ErgoFullBlock(realHeader, BlockTransactions(realHeader.id, txs), Extension(realHeader), Some(adProofs))
      us = us.applyModifier(fb).get
      height = height + 1
    }
  }

  property("concurrent applyModifier() and proofsForTransactions()") {
    implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

    var bh = BoxHolder(Seq(genesisEmissionBox))
    var us = createUtxoState(bh)

    var height: Int = ErgoHistory.GenesisHeight
    // generate chain of correct full blocks
    val chain = (0 until 10) map { _ =>
      val header = defaultHeaderGen.sample.value
      val t = validTransactionsFromBoxHolder(bh, new Random(height))
      val txs = t._1
      bh = t._2
      val (adProofBytes, adDigest) = us.proofsForTransactions(txs).get
      val realHeader = header.copy(stateRoot = adDigest, ADProofsRoot = ADProofs.proofDigest(adProofBytes), height = height)
      val adProofs = ADProofs(realHeader.id, adProofBytes)
      height = height + 1
      val fb = ErgoFullBlock(realHeader, BlockTransactions(realHeader.id, txs), Extension(realHeader), Some(adProofs))
      us = us.applyModifier(fb).get
      fb
    }
    // create new genesis state
    var us2 = createUtxoState(BoxHolder(Seq(genesisEmissionBox)))
    val stateReader = us2.getReader.asInstanceOf[UtxoState]
    // parallel thread that generates proofs
    Future {
      (0 until 1000) foreach { _ =>
        Try {
          val boxes = stateReader.randomBox().toSeq
          val txs = validTransactionsFromBoxes(400, boxes, new Random)._1
          stateReader.proofsForTransactions(txs).get
        }
      }
    }
    // apply chain of headers full block to state
    chain.foreach { fb =>
      us2 = us2.applyModifier(fb).get
    }
  }

  property("proofsForTransactions() to be deterministic") {
    forAll(boxesHolderGen) { bh =>
      val us = createUtxoState(bh)
      val txs = validTransactionsFromBoxHolder(bh)._1

      val (proof1, digest1) = us.proofsForTransactions(txs).get
      val (proof2, digest2) = us.proofsForTransactions(txs).get

      ADProofs.proofDigest(proof1) shouldBe ADProofs.proofDigest(proof2)
      digest1 shouldBe digest2
    }
  }

  property("applyTransactions() - simple case") {
    forAll(boxesHolderGen) { bh =>
      val txs = validTransactionsFromBoxHolder(bh)._1

      val created = txs.flatMap(_.outputs.map(_.id)).map(ByteArrayWrapper.apply)
      val boxIds = txs.flatMap(_.inputs.map(_.boxId)).map(ByteArrayWrapper.apply)
      boxIds.distinct.size shouldBe boxIds.size
      val toRemove = boxIds.filterNot(id => created.contains(id))
      toRemove.foreach(id => bh.get(id) should not be None)

      val us = createUtxoState(bh)
      bh.sortedBoxes.foreach(box => us.boxById(box.id) should not be None)
      val digest = us.proofsForTransactions(txs).get._2
      val wBlock = invalidErgoFullBlockGen.sample.get
      val block = wBlock.copy(header = wBlock.header.copy(height = 1))
      val newSC = us.stateContext.appendFullBlock(block, votingSettings).get
      us.applyTransactions(txs, digest, newSC).get
    }
  }

  property("applyTransactions() - a transaction is spending an output created by a previous transaction") {
    val header = defaultHeaderGen.sample.get
    forAll(boxesHolderGen) { bh =>
      val txsFromHolder = validTransactionsFromBoxHolder(bh)._1

      val boxToSpend = txsFromHolder.last.outputs.head

      val spendingTxInput = Input(boxToSpend.id, emptyProverResult)
      val spendingTx = ErgoTransaction(
        IndexedSeq(spendingTxInput),
        IndexedSeq(new ErgoBoxCandidate(boxToSpend.value, Values.TrueLeaf, creationHeight = startHeight)))

      val txs = txsFromHolder :+ spendingTx

      val us = createUtxoState(bh)
      val digest = us.proofsForTransactions(txs).get._2

      val header = invalidHeaderGen.sample.get.copy(stateRoot = digest, height = 1)
      val fb = new ErgoFullBlock(header, new BlockTransactions(header.id, txs), Extension(header), None)
      val newSC = us.stateContext.appendFullBlock(fb, votingSettings).get
      us.applyTransactions(txs, digest, newSC).get
    }
  }

  property("proofsForTransactions() fails if a transaction is spending an output created by a follow-up transaction") {
    forAll(boxesHolderGen) { bh =>
      val txsFromHolder = validTransactionsFromBoxHolder(bh)._1

      val boxToSpend = txsFromHolder.last.outputs.head

      val spendingTxInput = Input(boxToSpend.id, emptyProverResult)
      val spendingTx = ErgoTransaction(
        IndexedSeq(spendingTxInput),
        IndexedSeq(new ErgoBoxCandidate(boxToSpend.value, Values.TrueLeaf, creationHeight = startHeight)))

      val txs = spendingTx +: txsFromHolder

      val us = createUtxoState(bh)
      us.proofsForTransactions(txs).isSuccess shouldBe false
    }
  }

  property("applyModifier() - valid full block") {
    forAll(boxesHolderGen) { bh =>
      val us = createUtxoState(bh)
      bh.sortedBoxes.foreach(box => us.boxById(box.id) should not be None)

      val block = validFullBlock(parentOpt = None, us, bh)
      us.applyModifier(block).get
    }
  }

  property("applyModifier() - invalid block") {
    forAll(invalidErgoFullBlockGen) { b =>
      val state = createUtxoState()._1
      state.applyModifier(b).isFailure shouldBe true
    }
  }

  property("applyModifier() - valid full block after invalid one") {
    val (us, bh) = createUtxoState()
    val validBlock = validFullBlock(parentOpt = None, us, bh)

    //Different state
    val (us2, bh2) = {
      lazy val initialBoxes: Seq[ErgoBox] = (1 to 1).map(_ => ergoBoxGenNoProp.sample.get)

      val bh = BoxHolder(initialBoxes)

      createUtxoState(bh) -> bh
    }
    val invalidBlock = validFullBlock(parentOpt = None, us2, bh2)

    us.applyModifier(invalidBlock).isSuccess shouldBe false
    us.applyModifier(validBlock).isSuccess shouldBe true
  }


  property("2 forks switching") {
    val (us, bh) = createUtxoState()
    val genesis = validFullBlock(parentOpt = None, us, bh)
    val wusAfterGenesis = WrappedUtxoState(us, bh, stateConstants).applyModifier(genesis).get
    val chain1block1 = validFullBlock(Some(genesis.header), wusAfterGenesis)
    val wusChain1Block1 = wusAfterGenesis.applyModifier(chain1block1).get
    val chain1block2 = validFullBlock(Some(chain1block1.header), wusChain1Block1)

    val (us2, bh2) = createUtxoState()
    val wus2AfterGenesis = WrappedUtxoState(us2, bh2, stateConstants).applyModifier(genesis).get
    val chain2block1 = validFullBlock(Some(genesis.header), wus2AfterGenesis)
    val wusChain2Block1 = wus2AfterGenesis.applyModifier(chain2block1).get
    val chain2block2 = validFullBlock(Some(chain2block1.header), wusChain2Block1)

    var (state, _) = createUtxoState()
    state = state.applyModifier(genesis).get

    state = state.applyModifier(chain1block1).get

    state = state.rollbackTo(idToVersion(genesis.id)).get
    state = state.applyModifier(chain2block1).get
    state = state.applyModifier(chain2block2).get

    state = state.rollbackTo(idToVersion(genesis.id)).get
    state = state.applyModifier(chain1block1).get
    state = state.applyModifier(chain1block2).get

  }

  property("rollback n blocks and apply again") {
    forAll(boxesHolderGen, smallPositiveInt) { (bh, depth) =>
      whenever(depth > 0 && depth <= 5) {
        val us = createUtxoState(bh)
        bh.sortedBoxes.foreach(box => us.boxById(box.id) should not be None)
        val genesis = validFullBlock(parentOpt = None, us, bh)
        val wusAfterGenesis = WrappedUtxoState(us, bh, stateConstants).applyModifier(genesis).get
        wusAfterGenesis.rootHash shouldEqual genesis.header.stateRoot

        val (finalState: WrappedUtxoState, chain: Seq[ErgoFullBlock]) = (0 until depth)
          .foldLeft((wusAfterGenesis, Seq(genesis))) { (sb, _) =>
            val state = sb._1
            val block = validFullBlock(parentOpt = Some(sb._2.last.header), state)
            (state.applyModifier(block).get, sb._2 ++ Seq(block))
          }
        val finalRoot = finalState.rootHash
        finalRoot shouldEqual chain.last.header.stateRoot

        val rollbackedState = finalState.rollbackTo(idToVersion(genesis.id)).get
        rollbackedState.rootHash shouldEqual genesis.header.stateRoot

        val finalState2: WrappedUtxoState = chain.tail.foldLeft(rollbackedState) { (state, block) =>
          state.applyModifier(block).get
        }

        finalState2.rootHash shouldEqual finalRoot
      }
    }
  }

}
