/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.cleanup.javadsl

import java.util.concurrent.CompletionStage

import scala.collection.immutable
import scala.compat.java8.FutureConverters._

import akka.Done
import akka.actor.ClassicActorSystemProvider
import akka.annotation.ApiMayChange
import akka.persistence.r2dbc.cleanup.scaladsl

/**
 * Java API: Tool for deleting all events and/or snapshots for a given list of `persistenceIds` without using persistent
 * actors. It's important that the actors with corresponding `persistenceId` are not running at the same time as using
 * the tool.
 *
 * WARNING: deleting events is generally discouraged in event sourced systems.
 *
 * If `neverUsePersistenceIdAgain` is `true` then the highest used sequence number is deleted and the `persistenceId`
 * should not be used again, since it would be confusing to reuse the same sequence numbers for new events.
 *
 * When a list of `persistenceIds` are given they are deleted sequentially in the order of the list. It's possible to
 * parallelize the deletes by running several cleanup operations at the same time operating on different sets of
 * `persistenceIds`.
 */
@ApiMayChange
final class EventSourcedCleanup private (delegate: scaladsl.EventSourcedCleanup) {

  def this(systemProvider: ClassicActorSystemProvider, configPath: String) =
    this(new scaladsl.EventSourcedCleanup(systemProvider, configPath))

  def this(systemProvider: ClassicActorSystemProvider) =
    this(systemProvider, "akka.persistence.r2dbc.cleanup")

  /**
   * Delete all events before a sequenceNr for the given persistence id. Snapshots are not deleted.
   *
   * @param persistenceId
   *   the persistence id to delete for
   * @param toSequenceNr
   *   sequence nr (inclusive) to delete up to
   */
  def deleteEventsTo(persistenceId: String, toSequenceNr: Long): CompletionStage[Done] =
    delegate.deleteEventsTo(persistenceId, toSequenceNr).toJava

  /**
   * Delete all events related to one single `persistenceId`. Snapshots are not deleted.
   */
  def deleteAllEvents(persistenceId: String, neverUsePersistenceIdAgain: Boolean): CompletionStage[Done] =
    delegate.deleteAllEvents(persistenceId, neverUsePersistenceIdAgain).toJava

  /**
   * Delete all events related to the given list of `persistenceIds`. Snapshots are not deleted.
   */
  def deleteAllEvents(
      persistenceIds: immutable.Seq[String],
      neverUsePersistenceIdAgain: Boolean): CompletionStage[Done] =
    delegate.deleteAllEvents(persistenceIds, neverUsePersistenceIdAgain).toJava

  /**
   * Delete snapshots related to one single `persistenceId`. Events are not deleted.
   */
  def deleteSnapshot(persistenceId: String): CompletionStage[Done] =
    delegate.deleteSnapshot(persistenceId).toJava

  /**
   * Delete all snapshots related to the given list of `persistenceIds`. Events are not deleted.
   */
  def deleteSnapshots(persistenceIds: immutable.Seq[String]): CompletionStage[Done] =
    delegate.deleteSnapshots(persistenceIds).toJava

  /**
   * Deletes all events for the given persistence id from before the snapshot. The snapshot is not deleted. The event
   * with the same sequence number as the remaining snapshot is deleted.
   */
  def cleanupBeforeSnapshot(persistenceId: String): CompletionStage[Done] =
    delegate.cleanupBeforeSnapshot(persistenceId).toJava

  /**
   * See single persistenceId overload for what is done for each persistence id
   */
  def cleanupBeforeSnapshot(persistenceIds: immutable.Seq[String]): CompletionStage[Done] =
    delegate.cleanupBeforeSnapshot(persistenceIds).toJava

  /**
   * Delete everything related to one single `persistenceId`. All events and snapshots are deleted.
   */
  def deleteAll(persistenceId: String, neverUsePersistenceIdAgain: Boolean): CompletionStage[Done] =
    delegate.deleteAll(persistenceId, neverUsePersistenceIdAgain).toJava

  /**
   * Delete everything related to the given list of `persistenceIds`. All events and snapshots are deleted.
   */
  def deleteAll(persistenceIds: immutable.Seq[String], neverUsePersistenceIdAgain: Boolean): CompletionStage[Done] =
    delegate.deleteAll(persistenceIds, neverUsePersistenceIdAgain).toJava

}
