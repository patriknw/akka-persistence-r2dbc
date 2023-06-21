/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.internal.postgres

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.LoggerOps
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.r2dbc.R2dbcSettings
import akka.persistence.r2dbc.internal.BySliceQuery.Buckets
import akka.persistence.r2dbc.internal.BySliceQuery.Buckets.Bucket
import akka.persistence.r2dbc.internal.InstantFactory
import akka.persistence.r2dbc.internal.PayloadCodec
import akka.persistence.r2dbc.internal.PayloadCodec.RichRow
import akka.persistence.r2dbc.internal.PayloadCodec.RichStatement
import akka.persistence.r2dbc.internal.R2dbcExecutor
import akka.persistence.r2dbc.internal.SnapshotDao
import akka.persistence.r2dbc.internal.Sql.Interpolation
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.Source
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Row
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * INTERNAL API
 */
private[r2dbc] object PostgresSnapshotDao {
  private val log: Logger = LoggerFactory.getLogger(classOf[PostgresSnapshotDao])
}

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class PostgresSnapshotDao(settings: R2dbcSettings, connectionFactory: ConnectionFactory)(implicit
    ec: ExecutionContext,
    system: ActorSystem[_])
    extends SnapshotDao {
  import SnapshotDao._

  protected def log: Logger = PostgresSnapshotDao.log

  protected val snapshotTable = settings.snapshotsTableWithSchema
  private implicit val snapshotPayloadCodec: PayloadCodec = settings.snapshotPayloadCodec
  private val r2dbcExecutor = new R2dbcExecutor(connectionFactory, log, settings.logDbCallsExceeding)(ec, system)
  private val queryDao = settings.connectionFactorySettings.dialect.createQueryDao(settings, connectionFactory)

  protected def createUpsertSql: String =
    sql"""
      INSERT INTO $snapshotTable
      (slice, entity_type, persistence_id, seq_nr, db_timestamp, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (persistence_id)
      DO UPDATE SET
        seq_nr = excluded.seq_nr,
        db_timestamp = excluded.db_timestamp,
        write_timestamp = excluded.write_timestamp,
        snapshot = excluded.snapshot,
        ser_id = excluded.ser_id,
        ser_manifest = excluded.ser_manifest,
        meta_payload = excluded.meta_payload,
        meta_ser_id = excluded.meta_ser_id,
        meta_ser_manifest = excluded.meta_ser_manifest"""

  private val upsertSql = createUpsertSql

  private def selectSql(criteria: SnapshotSelectionCriteria): String = {
    val maxSeqNrCondition =
      if (criteria.maxSequenceNr != Long.MaxValue) " AND seq_nr <= ?"
      else ""

    val minSeqNrCondition =
      if (criteria.minSequenceNr > 0L) " AND seq_nr >= ?"
      else ""

    val maxTimestampCondition =
      if (criteria.maxTimestamp != Long.MaxValue) " AND write_timestamp <= ?"
      else ""

    val minTimestampCondition =
      if (criteria.minTimestamp != 0L) " AND write_timestamp >= ?"
      else ""

    sql"""
      SELECT slice, persistence_id, seq_nr, db_timestamp, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest
      FROM $snapshotTable
      WHERE persistence_id = ?
      $maxSeqNrCondition $minSeqNrCondition $maxTimestampCondition $minTimestampCondition
      LIMIT 1"""
  }

  private def deleteSql(criteria: SnapshotSelectionCriteria): String = {
    val maxSeqNrCondition =
      if (criteria.maxSequenceNr != Long.MaxValue) " AND seq_nr <= ?"
      else ""

    val minSeqNrCondition =
      if (criteria.minSequenceNr > 0L) " AND seq_nr >= ?"
      else ""

    val maxTimestampCondition =
      if (criteria.maxTimestamp != Long.MaxValue) " AND write_timestamp <= ?"
      else ""

    val minTimestampCondition =
      if (criteria.minTimestamp != 0L) " AND write_timestamp >= ?"
      else ""

    sql"""
      DELETE FROM $snapshotTable
      WHERE persistence_id = ?
      $maxSeqNrCondition $minSeqNrCondition $maxTimestampCondition $minTimestampCondition"""
  }

  private val currentDbTimestampSql =
    sql"SELECT CURRENT_TIMESTAMP AS db_timestamp"

  protected def snapshotsBySlicesRangeSql(minSlice: Int, maxSlice: Int): String = {

    sql"""
      SELECT slice, persistence_id, seq_nr, db_timestamp, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest
      FROM $snapshotTable
      WHERE entity_type = ?
      AND ${sliceCondition(minSlice, maxSlice)}
      AND db_timestamp >= ?
      ORDER BY db_timestamp, seq_nr
      LIMIT ?"""
  }

  private def selectBucketsSql(entityType: String, minSlice: Int, maxSlice: Int): String = {
    sql"""
     SELECT extract(EPOCH from db_timestamp)::BIGINT / 10 AS bucket, count(*) AS count
     FROM $snapshotTable
     WHERE entity_type = ?
     AND ${sliceCondition(minSlice, maxSlice)}
     AND db_timestamp >= ? AND db_timestamp <= ?
     GROUP BY bucket ORDER BY bucket LIMIT ?
     """
  }

  protected def sliceCondition(minSlice: Int, maxSlice: Int): String =
    s"slice in (${(minSlice to maxSlice).mkString(",")})"

  private def collectSerializedSnapshot(entityType: String, row: Row): SerializedSnapshotRow =
    SerializedSnapshotRow(
      row.get[Integer]("slice", classOf[Integer]),
      entityType,
      row.get("persistence_id", classOf[String]),
      row.get[java.lang.Long]("seq_nr", classOf[java.lang.Long]),
      row.get("db_timestamp", classOf[Instant]),
      row.get[java.lang.Long]("write_timestamp", classOf[java.lang.Long]),
      row.getPayload("snapshot"),
      row.get[Integer]("ser_id", classOf[Integer]),
      row.get("ser_manifest", classOf[String]), {
        val metaSerializerId = row.get("meta_ser_id", classOf[Integer])
        if (metaSerializerId eq null) None
        else
          Some(
            SerializedSnapshotMetadata(
              row.get("meta_payload", classOf[Array[Byte]]),
              metaSerializerId,
              row.get("meta_ser_manifest", classOf[String])))
      })

  override def load(
      persistenceId: String,
      criteria: SnapshotSelectionCriteria): Future[Option[SerializedSnapshotRow]] = {
    val entityType = PersistenceId.extractEntityType(persistenceId)
    r2dbcExecutor
      .select(s"select snapshot [$persistenceId], criteria: [$criteria]")(
        { connection =>
          val statement = connection
            .createStatement(selectSql(criteria))
            .bind(0, persistenceId)

          var bindIdx = 0
          if (criteria.maxSequenceNr != Long.MaxValue) {
            bindIdx += 1
            statement.bind(bindIdx, criteria.maxSequenceNr)
          }
          if (criteria.minSequenceNr > 0L) {
            bindIdx += 1
            statement.bind(bindIdx, criteria.minSequenceNr)
          }
          if (criteria.maxTimestamp != Long.MaxValue) {
            bindIdx += 1
            statement.bind(bindIdx, criteria.maxTimestamp)
          }
          if (criteria.minTimestamp > 0L) {
            bindIdx += 1
            statement.bind(bindIdx, criteria.minTimestamp)
          }
          statement
        },
        collectSerializedSnapshot(entityType, _))
      .map(_.headOption)(ExecutionContexts.parasitic)

  }

  def store(serializedRow: SerializedSnapshotRow): Future[Unit] = {
    queryDao.timestampOfEvent(serializedRow.persistenceId, serializedRow.seqNr).flatMap { eventTimestamp =>
      val timestamp = eventTimestamp.getOrElse(Instant.ofEpochMilli(serializedRow.writeTimestamp))

      r2dbcExecutor
        .updateOne(s"upsert snapshot [${serializedRow.persistenceId}], sequence number [${serializedRow.seqNr}]") {
          connection =>
            val statement =
              connection
                .createStatement(upsertSql)
                .bind(0, serializedRow.slice)
                .bind(1, serializedRow.entityType)
                .bind(2, serializedRow.persistenceId)
                .bind(3, serializedRow.seqNr)
                .bind(4, timestamp)
                .bind(5, serializedRow.writeTimestamp)
                .bindPayload(6, serializedRow.snapshot)
                .bind(7, serializedRow.serializerId)
                .bind(8, serializedRow.serializerManifest)

            serializedRow.metadata match {
              case Some(SerializedSnapshotMetadata(serializedMeta, serializerId, serializerManifest)) =>
                statement
                  .bind(9, serializedMeta)
                  .bind(10, serializerId)
                  .bind(11, serializerManifest)
              case None =>
                statement
                  .bindNull(9, classOf[Array[Byte]])
                  .bindNull(10, classOf[Integer])
                  .bindNull(11, classOf[String])
            }

            statement
        }
        .map(_ => ())(ExecutionContexts.parasitic)
    }
  }

  def delete(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    r2dbcExecutor.updateOne(s"delete snapshot [$persistenceId], criteria [$criteria]") { connection =>
      val statement = connection
        .createStatement(deleteSql(criteria))
        .bind(0, persistenceId)

      var bindIdx = 0
      if (criteria.maxSequenceNr != Long.MaxValue) {
        bindIdx += 1
        statement.bind(bindIdx, criteria.maxSequenceNr)
      }
      if (criteria.minSequenceNr > 0L) {
        bindIdx += 1
        statement.bind(bindIdx, criteria.minSequenceNr)
      }
      if (criteria.maxTimestamp != Long.MaxValue) {
        bindIdx += 1
        statement.bind(bindIdx, criteria.maxTimestamp)
      }
      if (criteria.minTimestamp > 0L) {
        bindIdx += 1
        statement.bind(bindIdx, criteria.minTimestamp)
      }
      statement
    }
  }.map(_ => ())(ExecutionContexts.parasitic)

  override def currentDbTimestamp(): Future[Instant] = {
    r2dbcExecutor
      .selectOne("select current db timestamp")(
        connection => connection.createStatement(currentDbTimestampSql),
        row => row.get("db_timestamp", classOf[Instant]))
      .map {
        case Some(time) => time
        case None       => throw new IllegalStateException(s"Expected one row for: $currentDbTimestampSql")
      }
  }

  override def rowsBySlices(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      fromTimestamp: Instant,
      toTimestamp: Option[Instant],
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean): Source[SerializedSnapshotRow, NotUsed] = {
    val result = r2dbcExecutor.select(s"select snapshotsBySlices [$minSlice - $maxSlice]")(
      connection => {
        val stmt = connection
          .createStatement(snapshotsBySlicesRangeSql(minSlice, maxSlice))
          .bind(0, entityType)
          .bind(1, fromTimestamp)
          .bind(2, settings.querySettings.bufferSize)
        stmt
      },
      collectSerializedSnapshot(entityType, _))

    if (log.isDebugEnabled)
      result.foreach(rows => log.debugN("Read [{}] snapshots from slices [{} - {}]", rows.size, minSlice, maxSlice))

    Source.futureSource(result.map(Source(_))).mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Counts for a bucket may become inaccurate when existing snapshots are updated since the timestamp is changed.
   */
  override def countBucketsMayChange: Boolean = true

  override def countBuckets(
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      fromTimestamp: Instant,
      limit: Int): Future[Seq[Bucket]] = {

    val toTimestamp = {
      val now = InstantFactory.now() // not important to use database time
      if (fromTimestamp == Instant.EPOCH)
        now
      else {
        // max buckets, just to have some upper bound
        val t = fromTimestamp.plusSeconds(Buckets.BucketDurationSeconds * limit + Buckets.BucketDurationSeconds)
        if (t.isAfter(now)) now else t
      }
    }

    val result = r2dbcExecutor.select(s"select bucket counts [$minSlice - $maxSlice]")(
      connection =>
        connection
          .createStatement(selectBucketsSql(entityType, minSlice, maxSlice))
          .bind(0, entityType)
          .bind(1, fromTimestamp)
          .bind(2, toTimestamp)
          .bind(3, limit),
      row => {
        val bucketStartEpochSeconds = row.get("bucket", classOf[java.lang.Long]).toLong * 10
        val count = row.get[java.lang.Long]("count", classOf[java.lang.Long]).toLong
        Bucket(bucketStartEpochSeconds, count)
      })

    if (log.isDebugEnabled)
      result.foreach(rows => log.debugN("Read [{}] bucket counts from slices [{} - {}]", rows.size, minSlice, maxSlice))

    result

  }

}
