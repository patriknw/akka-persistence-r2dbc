/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.snapshot

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.persistence.r2dbc.R2dbcSettings
import akka.persistence.r2dbc.internal.{ R2dbcExecutor, SliceUtils }
import akka.persistence.{ SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }
import akka.serialization.{ Serialization, Serializers }
import io.r2dbc.spi.{ ConnectionFactory, Row }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Failure

/**
 * INTERNAL API
 */
private[r2dbc] object SnapshotDao {
  private val log: Logger = LoggerFactory.getLogger(classOf[SnapshotDao])

  private def deserializeSnapshotRow(row: Row, serialization: Serialization): SelectedSnapshot =
    SelectedSnapshot(
      SnapshotMetadata(
        row.get("persistence_id", classOf[String]),
        row.get("sequence_number", classOf[java.lang.Long]),
        row.get("write_timestamp", classOf[java.lang.Long]), {
          val metaSerializerId = row.get("meta_ser_id", classOf[java.lang.Integer])
          if (metaSerializerId eq null) None
          else
            Some(
              serialization
                .deserialize(
                  row.get("meta_payload", classOf[Array[Byte]]),
                  metaSerializerId,
                  row.get("meta_ser_manifest", classOf[String]))
                .get)
        }),
      serialization
        .deserialize(
          row.get("snapshot", classOf[Array[Byte]]),
          row.get("ser_id", classOf[java.lang.Integer]),
          row.get("ser_manifest", classOf[String]))
        .get)
}

/**
 * INTERNAL API
 *
 * Class for doing db interaction outside of an actor to avoid mistakes in future callbacks
 */
@InternalApi
private[r2dbc] final class SnapshotDao(
    settings: R2dbcSettings,
    connectionFactory: ConnectionFactory,
    serialization: Serialization)(implicit ec: ExecutionContext, system: ActorSystem[_]) {
  import SnapshotDao._

  private val snapshotTable = settings.snapshotsTable
  private val r2dbcExecutor = new R2dbcExecutor(connectionFactory, log)(ec, system)

  private val insertSql =
    s"""INSERT INTO $snapshotTable (
          slice,
          entity_type_hint,
          persistence_id,
          sequence_number,
          write_timestamp,
          snapshot,
          ser_id,
          ser_manifest
        ) VALUES ($$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8)
        ON CONFLICT (slice, entity_type_hint, persistence_id, sequence_number)
        DO UPDATE SET
          write_timestamp = excluded.write_timestamp,
          snapshot = excluded.snapshot,
          ser_id = excluded.ser_id,
          ser_manifest = excluded.ser_manifest,
          meta_payload = null,
          meta_ser_id = null,
          meta_ser_manifest = null
        """

  private val insertSqlMeta =
    s"""INSERT INTO $snapshotTable (
          slice,
          entity_type_hint,
          persistence_id,
          sequence_number,
          write_timestamp,
          snapshot,
          ser_id,
          ser_manifest,
          meta_payload,
          meta_ser_id,
          meta_ser_manifest
        ) VALUES ($$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9, $$10, $$11)
        ON CONFLICT (slice, entity_type_hint, persistence_id, sequence_number)
        DO UPDATE SET
          write_timestamp = excluded.write_timestamp,
          snapshot = excluded.snapshot,
          ser_id = excluded.ser_id,
          ser_manifest = excluded.ser_manifest,
          meta_payload = excluded.meta_payload,
          meta_ser_id = excluded.meta_ser_id,
          meta_ser_manifest = excluded.meta_ser_manifest
        """

  def load(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    val entityTypeHint = SliceUtils.extractEntityTypeHintFromPersistenceId(persistenceId)
    val slice = SliceUtils.sliceForPersistenceId(persistenceId, settings.maxNumberOfSlices)

    var paramIdx = 3
    val selectSnapshots = s"SELECT * FROM $snapshotTable " +
      s"WHERE slice = $$1 AND entity_type_hint = $$2 AND persistence_id = $$3" +
      (if (criteria.maxSequenceNr != Long.MaxValue) {
         paramIdx += 1
         s" AND sequence_number <= $$$paramIdx"
       } else "") +
      (if (criteria.minSequenceNr > 0L) {
         paramIdx += 1
         s" AND sequence_number >= $$$paramIdx"
       } else "") +
      (if (criteria.maxTimestamp != Long.MaxValue) {
         paramIdx += 1
         s" AND write_timestamp <= $$$paramIdx"
       } else "") +
      (if (criteria.minTimestamp != 0L) {
         paramIdx += 1
         s" AND write_timestamp >= $$$paramIdx"
       } else "") +
      " ORDER BY sequence_number DESC"

    r2dbcExecutor
      .select(s"select snapshot [$persistenceId], criteria: [$criteria]")(
        { connection =>
          val statement = connection
            .createStatement(selectSnapshots)
            .bind(0, slice)
            .bind(1, entityTypeHint)
            .bind(2, persistenceId)

          var bindIdx = 2
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
        row =>
          Serialization.withTransportInformation(system.classicSystem.asInstanceOf[ExtendedActorSystem]) { () =>
            deserializeSnapshotRow(row, serialization)
          })
      .map(_.headOption)(ExecutionContexts.parasitic)

  }

  def store(metadata: SnapshotMetadata, value: Any): Future[Unit] =
    Serialization.withTransportInformation(system.classicSystem.asInstanceOf[ExtendedActorSystem]) { () =>
      val entityTypeHint = SliceUtils.extractEntityTypeHintFromPersistenceId(metadata.persistenceId)
      val slice = SliceUtils.sliceForPersistenceId(metadata.persistenceId, settings.maxNumberOfSlices)

      val insert =
        if (metadata.metadata.isEmpty) insertSql
        else insertSqlMeta

      val snapshot = value.asInstanceOf[AnyRef]
      val snapshotSerializer = serialization.findSerializerFor(snapshot)
      val snapshotManifest = Serializers.manifestFor(snapshotSerializer, snapshot)
      val serializedSnapshot = snapshotSerializer.toBinary(snapshot)

      val serializedMeta: Option[(Array[Byte], Int, String)] = metadata.metadata.map { meta =>
        val metaRef = meta.asInstanceOf[AnyRef]
        val metaSerializer = serialization.findSerializerFor(metaRef)
        val metaManifest = Serializers.manifestFor(metaSerializer, metaRef)
        (metaSerializer.toBinary(metaRef), metaSerializer.identifier, metaManifest)
      }

      r2dbcExecutor
        .updateOne(s"insert snapshot [${metadata.persistenceId}], sequence number [${metadata.sequenceNr}]") {
          connection =>
            val statement =
              connection
                .createStatement(insert)
                .bind(0, slice)
                .bind(1, entityTypeHint)
                .bind(2, metadata.persistenceId)
                .bind(3, metadata.sequenceNr)
                .bind(4, metadata.timestamp)
                .bind(5, serializedSnapshot)
                .bind(6, snapshotSerializer.identifier)
                .bind(7, snapshotManifest)

            serializedMeta.foreach { case (serializedMeta, serializerId, serializerManifest) =>
              statement
                .bind(8, serializedMeta)
                .bind(9, serializerId)
                .bind(10, serializerManifest)
            }

            statement
        }
        .map(_ => ())(ExecutionContexts.parasitic)
        .andThen { case Failure(ex) =>
          ex.printStackTrace()
        }
    }

  def delete(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    val entityTypeHint = SliceUtils.extractEntityTypeHintFromPersistenceId(persistenceId)
    val slice = SliceUtils.sliceForPersistenceId(persistenceId, settings.maxNumberOfSlices)

    var paramIdx = 3
    val deleteSnapshots = s"DELETE FROM $snapshotTable " +
      s"WHERE slice = $$1 AND entity_type_hint = $$2 AND persistence_id = $$3" +
      (if (criteria.maxSequenceNr != Long.MaxValue) {
         paramIdx += 1
         s" AND sequence_number <= $$$paramIdx"
       } else "") +
      (if (criteria.minSequenceNr > 0L) {
         paramIdx += 1
         s" AND sequence_number >= $$$paramIdx"
       } else "") +
      (if (criteria.maxTimestamp != Long.MaxValue) {
         paramIdx += 1
         s" AND write_timestamp <= $$$paramIdx"
       } else "") +
      (if (criteria.minTimestamp != 0L) {
         paramIdx += 1
         s" AND write_timestamp >= $$$paramIdx"
       } else "")

    r2dbcExecutor.updateOne(s"delete snapshot [$persistenceId], criteria [$criteria]") { connection =>
      val statement = connection
        .createStatement(deleteSnapshots)
        .bind(0, slice)
        .bind(1, entityTypeHint)
        .bind(2, persistenceId)

      var bindIdx = 2
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

}
