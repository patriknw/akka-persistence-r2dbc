/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.state.scaladsl

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import akka.Done
import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.Persistence
import akka.persistence.query.DeletedDurableState
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.query.TimestampOffset
import akka.persistence.query.UpdatedDurableState
import akka.persistence.query.scaladsl.DurableStateStorePagedPersistenceIdsQuery
import akka.persistence.query.typed.scaladsl.DurableStateStoreBySliceQuery
import akka.persistence.r2dbc.ConnectionFactoryProvider
import akka.persistence.r2dbc.R2dbcSettings
import akka.persistence.r2dbc.internal.BySliceQuery
import akka.persistence.r2dbc.internal.ContinuousQuery
import akka.persistence.r2dbc.state.scaladsl.DurableStateDao.SerializedStateRow
import akka.persistence.state.scaladsl.DurableStateUpdateStore
import akka.persistence.state.scaladsl.GetObjectResult
import akka.serialization.SerializationExtension
import akka.serialization.Serializers
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

object R2dbcDurableStateStore {
  val Identifier = "akka.persistence.r2dbc.state"

  private final case class PersistenceIdsQueryState(queryCount: Int, rowCount: Int, latestPid: String)
}

class R2dbcDurableStateStore[A](system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends DurableStateUpdateStore[A]
    with DurableStateStoreBySliceQuery[A]
    with DurableStateStorePagedPersistenceIdsQuery[A] {
  import R2dbcDurableStateStore.PersistenceIdsQueryState

  private val log = LoggerFactory.getLogger(getClass)
  private val sharedConfigPath = cfgPath.replaceAll("""\.state$""", "")
  private val settings = R2dbcSettings(system.settings.config.getConfig(sharedConfigPath))

  private val typedSystem = system.toTyped
  private val serialization = SerializationExtension(system)
  private val persistenceExt = Persistence(system)
  private val stateDao =
    new DurableStateDao(
      settings,
      ConnectionFactoryProvider(typedSystem).connectionFactoryFor(sharedConfigPath + ".connection-factory"))(
      typedSystem.executionContext,
      typedSystem)

  private val bySlice: BySliceQuery[SerializedStateRow, DurableStateChange[A]] = {
    val createEnvelope: (TimestampOffset, SerializedStateRow) => DurableStateChange[A] = (offset, row) => {
      row.payload match {
        case null =>
          // payload = null => lazy loaded for backtracking (ugly, but not worth changing UpdatedDurableState in Akka)
          new UpdatedDurableState(
            row.persistenceId,
            row.revision,
            null.asInstanceOf[A],
            offset,
            row.dbTimestamp.toEpochMilli)
        case Some(bytes) =>
          val payload = serialization.deserialize(bytes, row.serId, row.serManifest).get.asInstanceOf[A]
          new UpdatedDurableState(row.persistenceId, row.revision, payload, offset, row.dbTimestamp.toEpochMilli)
        case None =>
          new DeletedDurableState(row.persistenceId, row.revision, offset, row.dbTimestamp.toEpochMilli)
      }
    }

    val extractOffset: DurableStateChange[A] => TimestampOffset = env => env.offset.asInstanceOf[TimestampOffset]

    new BySliceQuery(stateDao, createEnvelope, extractOffset, settings, log)(typedSystem.executionContext)
  }

  override def getObject(persistenceId: String): Future[GetObjectResult[A]] = {
    implicit val ec: ExecutionContext = system.dispatcher
    stateDao.readState(persistenceId).map {
      case None => GetObjectResult(None, 0L)
      case Some(serializedRow) =>
        val payload =
          serializedRow.payload.map { bytes =>
            serialization
              .deserialize(bytes, serializedRow.serId, serializedRow.serManifest)
              .get
              .asInstanceOf[A]
          }
        GetObjectResult(payload, serializedRow.revision)
    }
  }

  /**
   * Insert the value if `revision` is 1, which will fail with `IllegalStateException` if there is already a stored
   * value for the given `persistenceId`. Otherwise update the value, which will fail with `IllegalStateException` if
   * the existing stored `revision` + 1 isn't equal to the given `revision`. This optimistic locking check can be
   * disabled with configuration `assert-single-writer`.
   */
  override def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] = {
    val valueAnyRef = value.asInstanceOf[AnyRef]
    val serialized = serialization.serialize(valueAnyRef).get
    val serializer = serialization.findSerializerFor(valueAnyRef)
    val manifest = Serializers.manifestFor(serializer, valueAnyRef)

    val serializedRow = SerializedStateRow(
      persistenceId,
      revision,
      DurableStateDao.EmptyDbTimestamp,
      DurableStateDao.EmptyDbTimestamp,
      Some(serialized),
      serializer.identifier,
      manifest,
      if (tag.isEmpty) Set.empty else Set(tag))

    stateDao.upsertState(serializedRow)

  }

  @deprecated(message = "Use the deleteObject overload with revision instead.", since = "1.0.0")
  override def deleteObject(persistenceId: String): Future[Done] =
    deleteObject(persistenceId, revision = 0)

  /**
   * Delete the value, which will fail with `IllegalStateException` if the existing stored `revision` + 1 isn't equal to
   * the given `revision`. This optimistic locking check can be disabled with configuration `assert-single-writer`. The
   * stored revision for the persistenceId is updated and next call to [[getObject]] will return the revision, but with
   * no value.
   *
   * If the given revision is `0` it will fully delete the value and revision from the database without any optimistic
   * locking check. Next call to [[getObject]] will then return revision 0 and no value.
   */
  override def deleteObject(persistenceId: String, revision: Long): Future[Done] = {
    stateDao.deleteState(persistenceId, revision)
  }

  override def sliceForPersistenceId(persistenceId: String): Int =
    persistenceExt.sliceForPersistenceId(persistenceId)

  override def sliceRanges(numberOfRanges: Int): immutable.Seq[Range] =
    persistenceExt.sliceRanges(numberOfRanges)

  override def currentChangesBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed] =
    bySlice.currentBySlices("currentChangesBySlices", entityType, minSlice, maxSlice, offset)

  override def changesBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed] =
    bySlice.liveBySlices("changesBySlices", entityType, minSlice, maxSlice, offset)

  override def currentPersistenceIds(afterId: Option[String], limit: Long): Source[String, NotUsed] =
    stateDao.persistenceIds(afterId, limit)

  def currentPersistenceIds(): Source[String, NotUsed] = {
    import settings.querySettings.persistenceIdsBufferSize
    def updateState(state: PersistenceIdsQueryState, pid: String): PersistenceIdsQueryState =
      state.copy(rowCount = state.rowCount + 1, latestPid = pid)

    def nextQuery(state: PersistenceIdsQueryState): (PersistenceIdsQueryState, Option[Source[String, NotUsed]]) = {
      if (state.queryCount == 0L || state.rowCount >= persistenceIdsBufferSize) {
        val newState = state.copy(rowCount = 0, queryCount = state.queryCount + 1)

        if (state.queryCount != 0 && log.isDebugEnabled())
          log.debugN(
            "persistenceIds query [{}] after [{}]. Found [{}] rows in previous query.",
            state.queryCount,
            state.latestPid,
            state.rowCount)

        newState -> Some(
          stateDao
            .persistenceIds(if (state.latestPid == "") None else Some(state.latestPid), persistenceIdsBufferSize))
      } else {
        if (log.isDebugEnabled)
          log.debug(
            "persistenceIds query [{}] completed. Found [{}] rows in previous query.",
            state.queryCount,
            state.rowCount)

        state -> None
      }
    }

    ContinuousQuery[PersistenceIdsQueryState, String](
      initialState = PersistenceIdsQueryState(0, 0, ""),
      updateState = updateState,
      delayNextQuery = _ => None,
      nextQuery = state => nextQuery(state))
      .mapMaterializedValue(_ => NotUsed)
  }

}
