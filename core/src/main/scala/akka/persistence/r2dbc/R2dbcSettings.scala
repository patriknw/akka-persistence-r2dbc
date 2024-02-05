/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc

import scala.collection.immutable
import akka.annotation.InternalApi
import akka.annotation.InternalStableApi
import akka.persistence.r2dbc.internal.codec.IdentityAdapter
import akka.persistence.r2dbc.internal.codec.PayloadCodec
import akka.persistence.r2dbc.internal.codec.SqlServerQueryAdapter
import akka.persistence.r2dbc.internal.codec.QueryAdapter
import akka.persistence.r2dbc.internal.codec.TagsCodec
import akka.persistence.r2dbc.internal.codec.TimestampCodec
import akka.persistence.r2dbc.internal.ConnectionFactorySettings
import akka.util.JavaDurationConverters._
import com.typesafe.config.Config

import java.util.Locale
import scala.collection.immutable
import scala.concurrent.duration._

/**
 * INTERNAL API
 */
@InternalStableApi
object R2dbcSettings {

  // must correspond to akka.persistence.Persistence.numberOfSlices
  private val NumberOfSlices = 1024

  def apply(config: Config): R2dbcSettings = {
    if (config.hasPath("dialect")) {
      throw new IllegalArgumentException(
        "Database dialect config has moved from 'akka.persistence.r2dbc.dialect' into the connection-factory block, " +
        "the old 'dialect' config entry must be removed, " +
        "see akka-persistence-r2dbc documentation for details on the new configuration scheme: " +
        "https://doc.akka.io/docs/akka-persistence-r2dbc/current/migration-guide.html")
    }

    val schema: Option[String] = Option(config.getString("schema")).filterNot(_.trim.isEmpty)

    val journalTable: String = config.getString("journal.table")

    def useJsonPayload(prefix: String) = config.getString(s"$prefix.payload-column-type").toUpperCase match {
      case "BYTEA"          => false
      case "JSONB" | "JSON" => true
      case t =>
        throw new IllegalStateException(
          s"Expected akka.persistence.r2dbc.$prefix.payload-column-type to be one of 'BYTEA', 'JSON' or 'JSONB' but found '$t'")
    }

    val journalPublishEvents: Boolean = config.getBoolean("journal.publish-events")

    val snapshotsTable: String = config.getString("snapshot.table")

    val durableStateTable: String = config.getString("state.table")

    val durableStateTableByEntityType: Map[String, String] =
      configToMap(config.getConfig("state.custom-table"))

    val durableStateAdditionalColumnClasses: Map[String, immutable.IndexedSeq[String]] = {
      import akka.util.ccompat.JavaConverters._
      val cfg = config.getConfig("state.additional-columns")
      cfg.root.unwrapped.asScala.toMap.map {
        case (k, v: java.util.List[_]) => k -> v.iterator.asScala.map(_.toString).toVector
        case (k, v)                    => k -> Vector(v.toString)
      }
    }

    val durableStateChangeHandlerClasses: Map[String, String] =
      configToMap(config.getConfig("state.change-handler"))

    val durableStateAssertSingleWriter: Boolean = config.getBoolean("state.assert-single-writer")

    val numberOfDataPartitions = config.getInt("data-partition.number-of-partitions")
    require(
      1 <= numberOfDataPartitions && numberOfDataPartitions <= NumberOfSlices,
      s"data-partition.number-of-partitions [$numberOfDataPartitions] must be between 1 and $NumberOfSlices")
    require(
      numberOfDataPartitions * (NumberOfSlices / numberOfDataPartitions) == NumberOfSlices,
      s"data-partition.number-of-partitions [$numberOfDataPartitions] must be a whole number divisor of " +
      s"numberOfSlices [$NumberOfSlices].")

    val numberOfDatabases = config.getInt("data-partition.number-of-databases")
    require(
      1 <= numberOfDatabases && numberOfDatabases <= numberOfDataPartitions,
      s"data-partition.number-of-databases [$numberOfDatabases] must be between 1 and $numberOfDataPartitions")
    require(
      numberOfDatabases * (numberOfDataPartitions / numberOfDatabases) == numberOfDataPartitions,
      s"data-partition.number-of-databases [$numberOfDatabases] must be a whole number divisor of " +
      s"data-partition.number-of-partitions [$numberOfDataPartitions].")
    require(
      durableStateChangeHandlerClasses.isEmpty || numberOfDatabases == 1,
      "Durable State ChangeHandler not supported with more than one data partition database.")

    val connectionFactorySettings =
      if (numberOfDatabases == 1) {
        if (!config.hasPath("connection-factory.dialect")) {
          throw new IllegalArgumentException(
            "The Akka Persistence R2DBC database config scheme has changed, the config needs to be updated " +
            "to choose database dialect using the connection-factory block, " +
            "see akka-persistence-r2dbc documentation for details on the new configuration scheme: " +
            "https://doc.akka.io/docs/akka-persistence-r2dbc/current/migration-guide.html")
        }
        Vector(ConnectionFactorySettings(config.getConfig("connection-factory")))
      } else {
        val rangeSize = numberOfDataPartitions / numberOfDatabases
        (0 until numberOfDatabases).map { i =>
          val configPropertyName = s"connection-factory-${i * rangeSize}-${i * rangeSize + rangeSize - 1}"
          ConnectionFactorySettings(config.getConfig(configPropertyName))
        }
      }

    require(
      connectionFactorySettings.map(_.dialect.name).toSet.size == 1,
      s"All dialects for the [${connectionFactorySettings.size}] database partitions must be the same.")

    val querySettings = new QuerySettings(config.getConfig("query"))

    val dbTimestampMonotonicIncreasing: Boolean = config.getBoolean("db-timestamp-monotonic-increasing")

    val useAppTimestamp: Boolean = config.getBoolean("use-app-timestamp")

    val logDbCallsExceeding: FiniteDuration =
      config.getString("log-db-calls-exceeding").toLowerCase(Locale.ROOT) match {
        case "off" => -1.millis
        case _     => config.getDuration("log-db-calls-exceeding").asScala
      }

    val codecSettings = {
      val journalPayloadCodec: PayloadCodec =
        if (useJsonPayload("journal")) PayloadCodec.JsonCodec else PayloadCodec.ByteArrayCodec
      val snapshotPayloadCodec: PayloadCodec =
        if (useJsonPayload("snapshot")) PayloadCodec.JsonCodec else PayloadCodec.ByteArrayCodec
      val durableStatePayloadCodec: PayloadCodec =
        if (useJsonPayload("state")) PayloadCodec.JsonCodec else PayloadCodec.ByteArrayCodec

      connectionFactorySettings.head.dialect.name match {
        case "sqlserver" =>
          new CodecSettings(
            journalPayloadCodec,
            snapshotPayloadCodec,
            durableStatePayloadCodec,
            tagsCodec = new TagsCodec.SqlServerTagsCodec(connectionFactorySettings.head.config),
            timestampCodec = TimestampCodec.SqlServerTimestampCodec,
            queryAdapter = SqlServerQueryAdapter)
        case "h2" =>
          new CodecSettings(
            journalPayloadCodec,
            snapshotPayloadCodec,
            durableStatePayloadCodec,
            tagsCodec = TagsCodec.H2TagsCodec,
            timestampCodec = TimestampCodec.H2TimestampCodec,
            queryAdapter = IdentityAdapter)
        case _ =>
          new CodecSettings(
            journalPayloadCodec,
            snapshotPayloadCodec,
            durableStatePayloadCodec,
            tagsCodec = TagsCodec.PostgresTagsCodec,
            timestampCodec = TimestampCodec.PostgresTimestampCodec,
            queryAdapter = IdentityAdapter)
      }
    }

    val cleanupSettings = new CleanupSettings(config.getConfig("cleanup"))
    val settingsFromConfig = new R2dbcSettings(
      schema,
      journalTable,
      journalPublishEvents,
      snapshotsTable,
      durableStateTable,
      durableStateAssertSingleWriter,
      logDbCallsExceeding,
      querySettings,
      dbTimestampMonotonicIncreasing,
      cleanupSettings,
      codecSettings,
      connectionFactorySettings,
      durableStateTableByEntityType,
      durableStateAdditionalColumnClasses,
      durableStateChangeHandlerClasses,
      useAppTimestamp,
      numberOfDataPartitions)

    // let the dialect trump settings that does not make sense for it
    settingsFromConfig.connectionFactorySettings.dialect.adaptSettings(settingsFromConfig)
  }

  private def configToMap(cfg: Config): Map[String, String] = {
    import akka.util.ccompat.JavaConverters._
    cfg.root.unwrapped.asScala.toMap.map { case (k, v) => k -> v.toString }
  }

  /**
   * The config paths for the connection factories that are used for the given number of data partitions and databases.
   */
  def connectionFactoryConfigPaths(
      baseConfigPath: String,
      numberOfDataPartitions: Int,
      numberOfDatabases: Int): immutable.IndexedSeq[String] = {
    if (numberOfDatabases == 1) {
      Vector(baseConfigPath)
    } else {
      val rangeSize = numberOfDataPartitions / numberOfDatabases
      (0 until numberOfDatabases).map { i =>
        s"$baseConfigPath-${i * rangeSize}-${i * rangeSize + rangeSize - 1}"
      }
    }
  }

}

/**
 * INTERNAL API
 */
@InternalStableApi
final class R2dbcSettings private (
    val schema: Option[String],
    val journalTable: String,
    val journalPublishEvents: Boolean,
    val snapshotsTable: String,
    val durableStateTable: String,
    val durableStateAssertSingleWriter: Boolean,
    val logDbCallsExceeding: FiniteDuration,
    val querySettings: QuerySettings,
    val dbTimestampMonotonicIncreasing: Boolean,
    val cleanupSettings: CleanupSettings,
    /** INTERNAL API */
    @InternalApi private[akka] val codecSettings: CodecSettings,
    _connectionFactorySettings: immutable.IndexedSeq[ConnectionFactorySettings],
    _durableStateTableByEntityType: Map[String, String],
    _durableStateAdditionalColumnClasses: Map[String, immutable.IndexedSeq[String]],
    _durableStateChangeHandlerClasses: Map[String, String],
    _useAppTimestamp: Boolean,
    val numberOfDataPartitions: Int) {
  import R2dbcSettings.NumberOfSlices

  /**
   * The journal table and schema name without data partition suffix.
   */
  val journalTableWithSchema: String = schema.map(_ + ".").getOrElse("") + journalTable

  /**
   * The journal table and schema name with data partition suffix for the given slice. When number-of-partitions is 1
   * the table name is without suffix.
   */
  def journalTableWithSchema(slice: Int): String =
    resolveTableName(journalTableWithSchema, slice)

  /**
   * The snapshot table and schema name without data partition suffix.
   */
  val snapshotsTableWithSchema: String = schema.map(_ + ".").getOrElse("") + snapshotsTable

  /**
   * The snapshot table and schema name with data partition suffix for the given slice. When number-of-partitions is 1
   * the table name is without suffix.
   */
  def snapshotTableWithSchema(slice: Int): String =
    resolveTableName(snapshotsTableWithSchema, slice)

  /**
   * The durable state table and schema name without data partition suffix.
   */
  val durableStateTableWithSchema: String = schema.map(_ + ".").getOrElse("") + durableStateTable

  /**
   * The durable state table and schema name with data partition suffix for the given slice. When number-of-partitions
   * is 1 the table name is without suffix.
   */
  def durableStateTableWithSchema(slice: Int): String =
    resolveTableName(durableStateTableWithSchema, slice)

  private def resolveTableName(table: String, slice: Int): String = {
    if (numberOfDataPartitions == 1)
      table
    else
      s"${table}_${dataPartition(slice)}"
  }

  /**
   * INTERNAL API: All journal tables and their the lower slice
   */
  @InternalApi private[akka] val allJournalTablesWithSchema: Map[String, Int] =
    resolveAllTableNames(journalTableWithSchema(_))

  /**
   * INTERNAL API: All snapshot tables and their the lower slice
   */
  @InternalApi private[akka] val allSnapshotTablesWithSchema: Map[String, Int] =
    resolveAllTableNames(snapshotTableWithSchema(_))

  /**
   * INTERNAL API: All durable state tables and their the lower slice
   */
  @InternalApi private[akka] val allDurableStateTablesWithSchema: Map[String, Int] =
    resolveAllTableNames(durableStateTableWithSchema(_))

  private def resolveAllTableNames(tableForSlice: Int => String): Map[String, Int] =
    (0 until NumberOfSlices).foldLeft(Map.empty[String, Int]) { case (acc, slice) =>
      val table = tableForSlice(slice)
      if (acc.contains(table)) acc
      else acc.updated(table, slice)
    }

  val numberOfDatabases: Int = _connectionFactorySettings.size

  val dataPartitionSliceRanges: immutable.IndexedSeq[Range] = {
    val rangeSize = NumberOfSlices / numberOfDataPartitions
    (0 until numberOfDataPartitions).map { i =>
      (i * rangeSize until i * rangeSize + rangeSize)
    }.toVector
  }

  val connectionFactorSliceRanges: immutable.IndexedSeq[Range] = {
    val rangeSize = NumberOfSlices / numberOfDatabases
    (0 until numberOfDatabases).map { i =>
      (i * rangeSize until i * rangeSize + rangeSize)
    }.toVector
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def isSliceRangeWithinSameDataPartition(minSlice: Int, maxSlice: Int): Boolean =
    numberOfDataPartitions == 1 || dataPartition(minSlice) == dataPartition(maxSlice)

  private def dataPartition(slice: Int): Int =
    slice / (NumberOfSlices / numberOfDataPartitions)

  /**
   * One of the supported dialects 'postgres', 'yugabyte', 'sqlserver' or 'h2'
   */
  def dialectName: String = connectionFactorySettings.dialect.name

  def getDurableStateTable(entityType: String): String =
    _durableStateTableByEntityType.getOrElse(entityType, durableStateTable)

  /**
   * The durable state table and schema name for the `entityType` without data partition suffix.
   */
  def getDurableStateTableWithSchema(entityType: String): String =
    durableStateTableByEntityTypeWithSchema.getOrElse(entityType, durableStateTableWithSchema)

  /**
   * The durable state table and schema name for the `entityType` with data partition suffix for the given slice. When
   * number-of-partitions is 1 the table name is without suffix.
   */
  def getDurableStateTableWithSchema(entityType: String, slice: Int): String =
    durableStateTableByEntityTypeWithSchema.get(entityType) match {
      case None        => durableStateTableWithSchema(slice)
      case Some(table) => resolveTableName(table, slice)
    }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def withDbTimestampMonotonicIncreasing(
      dbTimestampMonotonicIncreasing: Boolean): R2dbcSettings =
    copy(dbTimestampMonotonicIncreasing = dbTimestampMonotonicIncreasing)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def withUseAppTimestamp(useAppTimestamp: Boolean): R2dbcSettings =
    copy(useAppTimestamp = useAppTimestamp)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val durableStateTableByEntityTypeWithSchema: Map[String, String] =
    _durableStateTableByEntityType.map { case (entityType, table) =>
      entityType -> (schema.map(_ + ".").getOrElse("") + table)
    }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def durableStateChangeHandlerClasses: Map[String, String] =
    _durableStateChangeHandlerClasses

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def durableStateAdditionalColumnClasses: Map[String, immutable.IndexedSeq[String]] =
    _durableStateAdditionalColumnClasses

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def useAppTimestamp: Boolean = _useAppTimestamp

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def connectionFactorySettings: ConnectionFactorySettings =
    connectionFactorySettings(0)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def connectionFactorySettings(slice: Int): ConnectionFactorySettings = {
    val rangeSize = numberOfDataPartitions / numberOfDatabases
    val i = dataPartition(slice) / rangeSize
    _connectionFactorySettings(i)
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def resolveConnectionFactoryConfigPath(baseConfigPath: String, slice: Int): String = {
    if (numberOfDatabases == 1) {
      baseConfigPath
    } else {
      val rangeSize = numberOfDataPartitions / numberOfDatabases
      val i = dataPartition(slice) / rangeSize
      s"$baseConfigPath-${i * rangeSize}-${i * rangeSize + rangeSize - 1}"
    }
  }

  private def copy(
      schema: Option[String] = schema,
      journalTable: String = journalTable,
      journalPublishEvents: Boolean = journalPublishEvents,
      snapshotsTable: String = snapshotsTable,
      durableStateTable: String = durableStateTable,
      durableStateAssertSingleWriter: Boolean = durableStateAssertSingleWriter,
      logDbCallsExceeding: FiniteDuration = logDbCallsExceeding,
      querySettings: QuerySettings = querySettings,
      dbTimestampMonotonicIncreasing: Boolean = dbTimestampMonotonicIncreasing,
      cleanupSettings: CleanupSettings = cleanupSettings,
      codecSettings: CodecSettings = codecSettings,
      connectionFactorySettings: immutable.IndexedSeq[ConnectionFactorySettings] = _connectionFactorySettings,
      durableStateTableByEntityType: Map[String, String] = _durableStateTableByEntityType,
      durableStateAdditionalColumnClasses: Map[String, immutable.IndexedSeq[String]] =
        _durableStateAdditionalColumnClasses,
      durableStateChangeHandlerClasses: Map[String, String] = _durableStateChangeHandlerClasses,
      useAppTimestamp: Boolean = _useAppTimestamp,
      numberOfDataPartitions: Int = numberOfDataPartitions): R2dbcSettings =
    new R2dbcSettings(
      schema,
      journalTable,
      journalPublishEvents,
      snapshotsTable,
      durableStateTable,
      durableStateAssertSingleWriter,
      logDbCallsExceeding,
      querySettings,
      dbTimestampMonotonicIncreasing,
      cleanupSettings,
      codecSettings,
      connectionFactorySettings,
      durableStateTableByEntityType,
      durableStateAdditionalColumnClasses,
      durableStateChangeHandlerClasses,
      useAppTimestamp,
      numberOfDataPartitions)

  override def toString =
    s"R2dbcSettings(dialectName=$dialectName, schema=$schema, journalTable=$journalTable, snapshotsTable=$snapshotsTable, durableStateTable=$durableStateTable, logDbCallsExceeding=$logDbCallsExceeding, dbTimestampMonotonicIncreasing=$dbTimestampMonotonicIncreasing, useAppTimestamp=$useAppTimestamp, numberOfDataPartitions=$numberOfDataPartitions)"
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class QuerySettings(config: Config) {
  val refreshInterval: FiniteDuration = config.getDuration("refresh-interval").asScala
  val behindCurrentTime: FiniteDuration = config.getDuration("behind-current-time").asScala
  val backtrackingEnabled: Boolean = config.getBoolean("backtracking.enabled")
  val backtrackingWindow: FiniteDuration = config.getDuration("backtracking.window").asScala
  val backtrackingBehindCurrentTime: FiniteDuration = config.getDuration("backtracking.behind-current-time").asScala
  val bufferSize: Int = config.getInt("buffer-size")
  val persistenceIdsBufferSize: Int = config.getInt("persistence-ids.buffer-size")
  val deduplicateCapacity: Int = config.getInt("deduplicate-capacity")
  val startFromSnapshotEnabled: Boolean = config.getBoolean("start-from-snapshot.enabled")
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class ConnectionPoolSettings(config: Config) {
  val initialSize: Int = config.getInt("initial-size")
  val maxSize: Int = config.getInt("max-size")
  val maxIdleTime: FiniteDuration = config.getDuration("max-idle-time").asScala
  val maxLifeTime: FiniteDuration = config.getDuration("max-life-time").asScala

  val acquireTimeout: FiniteDuration = config.getDuration("acquire-timeout").asScala
  val acquireRetry: Int = config.getInt("acquire-retry")

  val validationQuery: String = config.getString("validation-query")

  val closeCallsExceeding: Option[FiniteDuration] =
    config.getString("close-calls-exceeding").toLowerCase(Locale.ROOT) match {
      case "off" => None
      case _     => Some(config.getDuration("close-calls-exceeding").asScala)
    }
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class PublishEventsDynamicSettings(config: Config) {
  val throughputThreshold: Int = config.getInt("throughput-threshold")
  val throughputCollectInterval: FiniteDuration = config.getDuration("throughput-collect-interval").asScala
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class CodecSettings(
    val journalPayloadCodec: PayloadCodec,
    val snapshotPayloadCodec: PayloadCodec,
    val durableStatePayloadCodec: PayloadCodec,
    val tagsCodec: TagsCodec,
    val timestampCodec: TimestampCodec,
    val queryAdapter: QueryAdapter) {

  // implicits that can be imported
  object JournalImplicits {
    implicit def journalPayloadCodec: PayloadCodec = CodecSettings.this.journalPayloadCodec
    implicit def tagsCodec: TagsCodec = CodecSettings.this.tagsCodec
    implicit def timestampCodec: TimestampCodec = CodecSettings.this.timestampCodec
    implicit def queryAdapter: QueryAdapter = CodecSettings.this.queryAdapter
  }
  object SnapshotImplicits {
    implicit def snapshotPayloadCodec: PayloadCodec = CodecSettings.this.snapshotPayloadCodec
    implicit def tagsCodec: TagsCodec = CodecSettings.this.tagsCodec
    implicit def timestampCodec: TimestampCodec = CodecSettings.this.timestampCodec
    implicit def queryAdapter: QueryAdapter = CodecSettings.this.queryAdapter
  }
  object DurableStateImplicits {
    implicit def durableStatePayloadCodec: PayloadCodec = CodecSettings.this.durableStatePayloadCodec
    implicit def tagsCodec: TagsCodec = CodecSettings.this.tagsCodec
    implicit def timestampCodec: TimestampCodec = CodecSettings.this.timestampCodec
    implicit def queryAdapter: QueryAdapter = CodecSettings.this.queryAdapter
  }
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class CleanupSettings(config: Config) {
  val logProgressEvery: Int = config.getInt("log-progress-every")
  val eventsJournalDeleteBatchSize: Int = config.getInt("events-journal-delete-batch-size")
}
