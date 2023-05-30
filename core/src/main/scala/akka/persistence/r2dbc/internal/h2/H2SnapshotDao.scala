/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.internal.h2

import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.persistence.r2dbc.R2dbcSettings
import akka.persistence.r2dbc.internal.Sql.Interpolation
import akka.persistence.r2dbc.internal.postgres.PostgresSnapshotDao
import io.r2dbc.spi.ConnectionFactory

import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 *
 * Class for doing db interaction outside of an actor to avoid mistakes in future callbacks
 */
@InternalApi
private[r2dbc] final class H2SnapshotDao(settings: R2dbcSettings, connectionFactory: ConnectionFactory)(implicit
    ec: ExecutionContext,
    system: ActorSystem[_])
    extends PostgresSnapshotDao(settings, connectionFactory) {

  // FIXME h2 merge + //  private val upsertSql = sql"""
  //    INSERT INTO $snapshotTable + //    (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest)
  //    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) + //    ON CONFLICT (persistence_id)
  //    DO UPDATE SET + //      seq_nr = excluded.seq_nr,
  //      write_timestamp = excluded.write_timestamp, + //      snapshot = excluded.snapshot,
  //      ser_id = excluded.ser_id, + //      ser_manifest = excluded.ser_manifest,
  //      meta_payload = excluded.meta_payload, + //      meta_ser_id = excluded.meta_ser_id,
  //      meta_ser_manifest = excluded.meta_ser_manifest"""
  override protected def createUpsertSql: String = sql"""
    MERGE INTO $snapshotTable
    (slice, entity_type, persistence_id, seq_nr, write_timestamp, snapshot, ser_id, ser_manifest, meta_payload, meta_ser_id, meta_ser_manifest)
    KEY (persistence_id)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  """

}
