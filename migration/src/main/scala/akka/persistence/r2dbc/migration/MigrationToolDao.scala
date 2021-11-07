/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.migration

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.Done
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.persistence.r2dbc.internal.R2dbcExecutor
import akka.persistence.r2dbc.journal.JournalDao.log
import io.r2dbc.spi.ConnectionFactory

/**
 * INTERNAL API
 */
@InternalApi private[r2dbc] object MigrationToolDao {
  final case class CurrentProgress(persistenceId: String, eventSeqNr: Long, snapshotSeqNr: Long)
}

/**
 * INTERNAL API
 */
@InternalApi private[r2dbc] class MigrationToolDao(connectionFactory: ConnectionFactory)(implicit
    ec: ExecutionContext,
    system: ActorSystem[_]) {
  import MigrationToolDao.CurrentProgress

  private val r2dbcExecutor = new R2dbcExecutor(connectionFactory, log)(ec, system)

  def createProgressTable(): Future[Done] = {
    r2dbcExecutor.executeDdl("create migration progress table") { connection =>
      connection.createStatement("""CREATE TABLE IF NOT EXISTS migration_progress(
        | persistence_id VARCHAR(255) NOT NULL,
        | event_sequence_number BIGINT,
        | snapshot_sequence_number BIGINT,
        | PRIMARY KEY(persistence_id)
        |)""".stripMargin)
    }
  }

  def updateEventProgress(persistenceId: String, seqNr: Long): Future[Done] = {
    r2dbcExecutor
      .updateOne(s"upsert migration progress [$persistenceId]") { connection =>
        connection
          .createStatement(
            "INSERT INTO migration_progress " +
            "(persistence_id, event_sequence_number)  " +
            "VALUES ($1, $2) " +
            "ON CONFLICT (persistence_id) " +
            "DO UPDATE SET " +
            "event_sequence_number = excluded.event_sequence_number")
          .bind(0, persistenceId)
          .bind(1, seqNr)
      }
      .map(_ => Done)(ExecutionContext.parasitic)
  }

  def updateSnapshotProgress(persistenceId: String, seqNr: Long): Future[Done] = {
    r2dbcExecutor
      .updateOne(s"upsert migration progress [$persistenceId]") { connection =>
        connection
          .createStatement(
            "INSERT INTO migration_progress " +
            "(persistence_id, snapshot_sequence_number)  " +
            "VALUES ($1, $2) " +
            "ON CONFLICT (persistence_id) " +
            "DO UPDATE SET " +
            "snapshot_sequence_number = excluded.snapshot_sequence_number")
          .bind(0, persistenceId)
          .bind(1, seqNr)
      }
      .map(_ => Done)(ExecutionContext.parasitic)
  }

  def currentProgress(persistenceId: String): Future[Option[CurrentProgress]] = {
    r2dbcExecutor.selectOne(s"read migration progress [$persistenceId]")(
      _.createStatement("SELECT * FROM migration_progress WHERE persistence_id = $1")
        .bind(0, persistenceId),
      row =>
        CurrentProgress(
          persistenceId,
          eventSeqNr = zeroIfNull(row.get("event_sequence_number", classOf[java.lang.Long])),
          snapshotSeqNr = zeroIfNull(row.get("snapshot_sequence_number", classOf[java.lang.Long]))))
  }

  private def zeroIfNull(n: java.lang.Long): Long =
    if (n eq null) 0L else n

}
