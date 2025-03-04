/*
 * Copyright (C) 2022 - 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.internal.sqlserver

import java.time.Instant

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import akka.annotation.InternalApi
import akka.persistence.r2dbc.internal.Dialect
import akka.persistence.r2dbc.internal.Sql.InterpolationWithAdapter
import akka.persistence.r2dbc.internal.codec.TimestampCodec.TimestampCodecRichStatement
import akka.persistence.r2dbc.internal.postgres.PostgresDurableStateDao
import akka.persistence.r2dbc.internal.postgres.PostgresDurableStateDao.EvaluatedAdditionalColumnBindings
import io.r2dbc.spi.Statement
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import akka.persistence.r2dbc.internal.InstantFactory
import akka.persistence.r2dbc.internal.R2dbcExecutorProvider
import akka.persistence.r2dbc.internal.Sql

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] object SqlServerDurableStateDao {
  private val log: Logger = LoggerFactory.getLogger(classOf[SqlServerDurableStateDao])
}

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] class SqlServerDurableStateDao(executorProvider: R2dbcExecutorProvider, dialect: Dialect)
    extends PostgresDurableStateDao(executorProvider, dialect) {
  import settings.codecSettings.DurableStateImplicits._

  require(settings.useAppTimestamp, "SqlServer requires akka.persistence.r2dbc.use-app-timestamp=on")

  private val sqlCache = Sql.Cache(settings.numberOfDataPartitions > 1)

  override def log: Logger = SqlServerDurableStateDao.log

  override protected def insertStateSql(
      slice: Int,
      entityType: String,
      additionalBindings: immutable.IndexedSeq[EvaluatedAdditionalColumnBindings]): String = {
    def createSql = {
      val stateTable = settings.getDurableStateTableWithSchema(entityType, slice)
      val additionalCols = additionalInsertColumns(additionalBindings)
      val additionalParams = additionalInsertParameters(additionalBindings)
      sql"""
      INSERT INTO $stateTable
      (slice, entity_type, persistence_id, revision, state_ser_id, state_ser_manifest, state_payload, tags$additionalCols, db_timestamp)
      VALUES (@slice, @entityType, @persistenceId, @revision, @stateSerId, @stateSerManifest, @statePayload, @tags$additionalParams, @now)"""
    }

    if (additionalBindings.isEmpty)
      sqlCache.get(slice, s"insertStateSql-$entityType")(createSql)
    else
      createSql // no cache
  }

  /**
   * here, the currentTimestamp is another query param. Binding is happening in the overridden method `bindTimestampNow`
   */
  override protected def updateStateSql(
      slice: Int,
      entityType: String,
      updateTags: Boolean,
      additionalBindings: immutable.IndexedSeq[EvaluatedAdditionalColumnBindings],
      currentTimestamp: String): String =
    super.updateStateSql(slice, entityType, updateTags, additionalBindings, currentTimestamp = "?")

  override def selectBucketsSql(entityType: String, minSlice: Int, maxSlice: Int): String = {
    sqlCache.get(minSlice, s"selectBucketsSql-$entityType-$minSlice-$maxSlice") {
      val stateTable = settings.getDurableStateTableWithSchema(entityType, minSlice)

      val subQuery =
        s"""
          select TOP(@limit) CAST(DATEDIFF(s,'1970-01-01 00:00:00',db_timestamp) AS BIGINT) / 10 AS bucket
          FROM $stateTable
          WHERE entity_type = @entityType
          AND ${sliceCondition(minSlice, maxSlice)}
          AND db_timestamp >= @fromTimestamp AND db_timestamp <= @toTimestamp
         """
      sql"""
       SELECT bucket,  count(*) as count from ($subQuery) as sub
       GROUP BY bucket ORDER BY bucket
       """
    }
  }

  override def bindSelectBucketSql(
      stmt: Statement,
      entityType: String,
      fromTimestamp: Instant,
      toTimestamp: Instant,
      limit: Int): Statement = stmt
    .bind("@limit", limit)
    .bind("@entityType", entityType)
    .bindTimestamp("@fromTimestamp", fromTimestamp)
    .bindTimestamp("@toTimestamp", toTimestamp)

  override protected def bindStateBySlicesRange(
      stmt: Statement,
      entityType: String,
      fromTimestamp: Instant,
      toTimestamp: Option[Instant],
      behindCurrentTime: FiniteDuration): Statement = {
    stmt
      .bind("@entityType", entityType)
      .bindTimestamp("@fromTimestamp", fromTimestamp)
    stmt.bind("@limit", settings.querySettings.bufferSize)
    if (behindCurrentTime > Duration.Zero) {
      stmt.bindTimestamp("@now", InstantFactory.now())
    }
    toTimestamp.foreach(until => stmt.bindTimestamp("@until", until))
    stmt
  }

  override def stateBySlicesRangeSql(
      entityType: String,
      maxDbTimestampParam: Boolean,
      behindCurrentTime: FiniteDuration,
      backtracking: Boolean,
      minSlice: Int,
      maxSlice: Int): String = {
    // not caching, too many combinations

    val behindCurrentTimeIntervalCondition: String =
      if (behindCurrentTime > Duration.Zero)
        s"AND db_timestamp < DATEADD(ms, -${behindCurrentTime.toMillis}, @now)"
      else ""

    val stateTable = settings.getDurableStateTableWithSchema(entityType, minSlice)

    def maxDbTimestampParamCondition =
      if (maxDbTimestampParam) s"AND db_timestamp < @until" else ""

    /**
     * since we enforce `use-app-timestamp=on`, should we use InstantFactor.now instead the actual read db timestamp
     * (SYSUTCDATETIME)?
     */
    val selectColumns =
      if (backtracking)
        "SELECT TOP(@limit) persistence_id, revision, db_timestamp, SYSUTCDATETIME() AS read_db_timestamp, state_ser_id "
      else
        "SELECT TOP(@limit) persistence_id, revision, db_timestamp, SYSUTCDATETIME() AS read_db_timestamp, state_ser_id, state_ser_manifest, state_payload "

    sql"""
    $selectColumns
    FROM $stateTable
    WHERE entity_type = @entityType
    AND ${sliceCondition(minSlice, maxSlice)}
    AND db_timestamp >= @fromTimestamp $maxDbTimestampParamCondition $behindCurrentTimeIntervalCondition
    ORDER BY db_timestamp, revision"""
  }

  override protected def bindTimestampNow(stmt: Statement, getAndIncIndex: () => Int): Statement =
    stmt.bindTimestamp(getAndIncIndex(), InstantFactory.now())

  override protected def persistenceIdsForEntityTypeAfterSql(table: String): String = {
    // not worth caching
    sql"SELECT TOP(@limit) persistence_id from $table WHERE persistence_id LIKE @persistenceIdLike AND persistence_id > @after ORDER BY persistence_id"
  }

  override protected def bindPersistenceIdsForEntityTypeAfterSql(
      stmt: Statement,
      entityType: String,
      likeStmtPostfix: String,
      after: String,
      limit: Long): Statement = {
    stmt
      .bind("@limit", limit)
      .bind("@persistenceIdLike", entityType + likeStmtPostfix)
      .bind("@after", after)
  }

  override protected def bindPersistenceIdsForEntityTypeSql(
      stmt: Statement,
      entityType: String,
      likeStmtPostfix: String,
      limit: Long): Statement = {
    stmt
      .bind("@limit", limit)
      .bind("@persistenceIdLike", entityType + likeStmtPostfix)
  }

  override protected def persistenceIdsForEntityTypeSql(table: String): String = {
    // not worth caching
    sql"SELECT TOP(@limit) persistence_id from $table WHERE persistence_id LIKE @persistenceIdLike ORDER BY persistence_id"
  }

  override protected def allPersistenceIdsSql(table: String): String = {
    // not worth caching
    sql"SELECT TOP(@limit) persistence_id from $table ORDER BY persistence_id"
  }

  override protected def allPersistenceIdsAfterSql(table: String): String = {
    // not worth caching
    sql"SELECT TOP(@limit) persistence_id from $table WHERE persistence_id > @persistenceId ORDER BY persistence_id"
  }

  override def bindAllPersistenceIdsAfterSql(stmt: Statement, after: String, limit: Long): Statement = stmt
    .bind("@persistenceId", after)
    .bind("@limit", limit)

  override def currentDbTimestamp(slice: Int): Future[Instant] = Future.successful(InstantFactory.now())

}
