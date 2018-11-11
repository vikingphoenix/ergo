package org.ergoplatform.nodeView.history.modifierprocessors

import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.modifiers.state.UtxoSnapshotChunk
import org.ergoplatform.nodeView.history.storage.HistoryStorage
import org.ergoplatform.settings.Algos
import scorex.core.consensus.History.ProgressInfo
import scorex.core.utils.ScorexEncoding
import scorex.util.ScorexLogging

import scala.util.{Failure, Success, Try}

/**
  * Contains all functions required by History to process UTXOSnapshotChunk
  */
trait UTXOSnapshotChunkProcessor extends ScorexLogging with ScorexEncoding {

  protected val historyStorage: HistoryStorage

  def process(m: UtxoSnapshotChunk): ProgressInfo[ErgoPersistentModifier] = {
    //TODO
    val toInsert = ???
    historyStorage.insert(Algos.idToBAW(m.id), Seq.empty, toInsert)
    ProgressInfo(None, Seq.empty, Seq(m), Seq.empty)
  }

  def validate(m: UtxoSnapshotChunk): Try[Unit] = if (historyStorage.contains(m.id)) {
    Failure(new Error(s"UTXOSnapshotChunk with id ${m.encodedId} is already in history"))
  } else {
    Success(Unit)
  }

}
