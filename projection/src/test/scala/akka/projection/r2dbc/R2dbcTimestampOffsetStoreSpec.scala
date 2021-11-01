/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.r2dbc

import java.time.Instant
import java.time.{ Duration => JDuration }

import scala.concurrent.ExecutionContext

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.persistence.r2dbc.query.TimestampOffset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.internal.ManagementState
import akka.projection.r2dbc.internal.R2dbcOffsetStore
import akka.projection.r2dbc.internal.R2dbcOffsetStore.Pid
import akka.projection.r2dbc.internal.R2dbcOffsetStore.Record
import akka.projection.r2dbc.internal.R2dbcOffsetStore.SeqNr
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

class R2dbcTimestampOffsetStoreSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system

  private val clock = new TestClock
  def tick(): Unit = clock.tick(JDuration.ofMillis(1))

  private val log = LoggerFactory.getLogger(getClass)

  private val settings = R2dbcProjectionSettings(testKit.system)

  private def createOffsetStore(projectionId: ProjectionId, customSettings: R2dbcProjectionSettings = settings) =
    new R2dbcOffsetStore(projectionId, system, customSettings, r2dbcExecutor)

  def createEnvelope(
      pid: Pid,
      seqNr: SeqNr,
      timestamp: Instant,
      readAfterMillis: Int,
      event: String): EventEnvelope[String] =
    EventEnvelope(
      TimestampOffset(timestamp, timestamp.plusMillis(readAfterMillis), Map(pid -> seqNr)),
      pid,
      seqNr,
      event,
      timestamp.toEpochMilli)

  private implicit val ec: ExecutionContext = system.executionContext

  "The R2dbcOffsetStore for TimestampOffset" must {

    "update TimestampOffset with one entry" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L))
      offsetStore.saveOffset(offset1).futureValue
      val readOffset1 = offsetStore.readOffset[TimestampOffset]()
      readOffset1.futureValue shouldBe Some(offset1)

      tick()
      val offset2 = TimestampOffset(clock.instant(), Map("p1" -> 4L))
      offsetStore.saveOffset(offset2).futureValue
      val readOffset2 = offsetStore.readOffset[TimestampOffset]()
      readOffset2.futureValue shouldBe Some(offset2) // yep, saveOffset overwrites previous
    }

    "update TimestampOffset with several entries" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L, "p2" -> 1L, "p3" -> 5L))
      offsetStore.saveOffset(offset1).futureValue
      val readOffset1 = offsetStore.readOffset[TimestampOffset]()
      readOffset1.futureValue shouldBe Some(offset1)

      tick()
      val offset2 = TimestampOffset(clock.instant(), Map("p1" -> 4L, "p3" -> 6L, "p4" -> 9L))
      offsetStore.saveOffset(offset2).futureValue
      val readOffset2 = offsetStore.readOffset[TimestampOffset]()
      // p2 is not included in read offset because it wasn't updated and has earlier timestamp
      readOffset2.futureValue shouldBe Some(offset2)
    }

    "update TimestampOffset when same timestamp" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L, "p2" -> 1L, "p3" -> 5L))
      offsetStore.saveOffset(offset1).futureValue
      val readOffset1 = offsetStore.readOffset[TimestampOffset]()
      readOffset1.futureValue shouldBe Some(offset1)

      // not tick, same timestamp
      val offset2 = TimestampOffset(clock.instant(), Map("p2" -> 2L, "p4" -> 9L))
      offsetStore.saveOffset(offset2).futureValue
      val readOffset2 = offsetStore.readOffset[TimestampOffset]()
      // all should be included since same timestamp
      val expectedOffset2 = TimestampOffset(clock.instant(), Map("p1" -> 3L, "p2" -> 2L, "p3" -> 5L, "p4" -> 9L))
      readOffset2.futureValue shouldBe Some(expectedOffset2)

      // saving new with later timestamp
      tick()
      val offset3 = TimestampOffset(clock.instant(), Map("p1" -> 4L))
      offsetStore.saveOffset(offset3).futureValue
      val readOffset3 = offsetStore.readOffset[TimestampOffset]()
      // then it should only contain that entry
      readOffset3.futureValue shouldBe Some(offset3)
    }

    "not update when earlier seqNr" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L))
      offsetStore.saveOffset(offset1).futureValue
      val readOffset1 = offsetStore.readOffset[TimestampOffset]()
      readOffset1.futureValue shouldBe Some(offset1)

      clock.setInstant(clock.instant().minusMillis(1))
      val offset2 = TimestampOffset(clock.instant(), Map("p1" -> 2L))
      offsetStore.saveOffset(offset2).futureValue
      val readOffset2 = offsetStore.readOffset[TimestampOffset]()
      readOffset2.futureValue shouldBe Some(offset1) // keeping offset1
    }

    "filter duplicates" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L, "p2" -> 1L, "p3" -> 5L))
      offsetStore.saveOffset(offset1).futureValue
      tick()
      val offset2 = TimestampOffset(clock.instant(), Map("p1" -> 4L, "p3" -> 6L, "p4" -> 9L))
      offsetStore.saveOffset(offset2).futureValue
      tick()
      val offset3 = TimestampOffset(clock.instant(), Map("p5" -> 10L))
      offsetStore.saveOffset(offset3).futureValue

      offsetStore.isDuplicate(Record("p5", 10, offset3.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p1", 4, offset2.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p3", 6, offset2.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p4", 9, offset2.timestamp)) shouldBe true

      offsetStore.isDuplicate(Record("p1", 3, offset1.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p2", 1, offset1.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p3", 5, offset1.timestamp)) shouldBe true

      offsetStore.isDuplicate(Record("p1", 2, offset1.timestamp.minusMillis(1))) shouldBe true
      offsetStore.isDuplicate(Record("p5", 9, offset3.timestamp.minusMillis(1))) shouldBe true

      offsetStore.isDuplicate(Record("p5", 11, offset3.timestamp)) shouldBe false
      offsetStore.isDuplicate(Record("p5", 12, offset3.timestamp.plusMillis(1))) shouldBe false

      offsetStore.isDuplicate(Record("p6", 1, offset3.timestamp.plusMillis(2))) shouldBe false
      offsetStore.isDuplicate(Record("p7", 1, offset3.timestamp.minusMillis(1))) shouldBe false
    }

    "accept known sequence numbers and reject unknown" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      val startTime = Instant.now()
      val offset1 = TimestampOffset(startTime, Map("p1" -> 3L, "p2" -> 1L, "p3" -> 5L))
      offsetStore.saveOffset(offset1).futureValue

      // seqNr 1 is always accepted
      val env1 = createEnvelope("p4", 1L, startTime.plusMillis(1), 10, "e4-1")
      offsetStore.isAccepted(env1) shouldBe true
      // but not if already inflight, seqNr 1 was accepted
      offsetStore.addInflight(env1)
      offsetStore.isAccepted(createEnvelope("p4", 1L, startTime.plusMillis(1), 10, "e4-1")) shouldBe false
      // subsequent seqNr is accepted
      val env2 = createEnvelope("p4", 2L, startTime.plusMillis(2), 10, "e4-2")
      offsetStore.isAccepted(env2) shouldBe true
      offsetStore.addInflight(env2)
      // but not when gap
      offsetStore.isAccepted(createEnvelope("p4", 4L, startTime.plusMillis(3), 10, "e4-4")) shouldBe false
      // and not if later already inflight, seqNr 2 was accepted
      offsetStore.isAccepted(createEnvelope("p4", 1L, startTime.plusMillis(1), 10, "e4-1")) shouldBe false

      // +1 to known is accepted
      val env3 = createEnvelope("p1", 4L, startTime.plusMillis(4), 10, "e1-4")
      offsetStore.isAccepted(env3) shouldBe true
      // but not same
      offsetStore.isAccepted(createEnvelope("p3", 5L, startTime, 10, "e3-5")) shouldBe false
      // but not same, even if it's 1
      offsetStore.isAccepted(createEnvelope("p2", 1L, startTime, 10, "e2-1")) shouldBe false
      // and not less
      offsetStore.isAccepted(createEnvelope("p3", 4L, startTime, 10, "e3-4")) shouldBe false
      offsetStore.addInflight(env3)

      // +1 to known, and then also subsequent are accepted (needed for grouped)
      val env4 = createEnvelope("p3", 6L, startTime.plusMillis(5), 10, "e3-6")
      offsetStore.isAccepted(env4) shouldBe true
      offsetStore.addInflight(env4)
      val env5 = createEnvelope("p3", 7L, startTime.plusMillis(6), 10, "e3-7")
      offsetStore.isAccepted(env5) shouldBe true
      offsetStore.addInflight(env5)
      val env6 = createEnvelope("p3", 8L, startTime.plusMillis(7), 10, "e3-8")
      offsetStore.isAccepted(env6) shouldBe true
      offsetStore.addInflight(env6)

      // reject unknown
      val env7 = createEnvelope("p5", 7L, startTime.plusMillis(8), 10, "e5-7")
      offsetStore.isAccepted(env7) shouldBe false
      // but ok when read later
      val env8 = createEnvelope("p5", 7L, startTime.plusMillis(5), 10000, "e5-7")
      offsetStore.isAccepted(env8) shouldBe true
      offsetStore.addInflight(env8)
      // and subsequent seqNr is accepted
      val env9 = createEnvelope("p5", 8L, startTime.plusMillis(9), 10, "e5-8")
      offsetStore.isAccepted(env9) shouldBe true
      offsetStore.addInflight(env9)

      // it's keeping the inflight that are not in the "stored" state
      offsetStore.getInflight() shouldBe Map("p1" -> 4L, "p3" -> 8, "p4" -> 2L, "p5" -> 8)
      // and they are removed from inflight once they have been stored
      offsetStore.saveOffset(TimestampOffset(startTime.plusMillis(2), Map("p4" -> 2L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plusMillis(9), Map("p5" -> 8L))).futureValue
      offsetStore.getInflight() shouldBe Map("p1" -> 4L, "p3" -> 8)
    }

    "update inflight on error and re-accept element" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      val startTime = Instant.now()

      val envelope1 = createEnvelope("p1", 1L, startTime.plusMillis(1), 10, "e1-1")
      val envelope2 = createEnvelope("p1", 2L, startTime.plusMillis(2), 10, "e1-2")
      val envelope3 = createEnvelope("p1", 3L, startTime.plusMillis(2), 10, "e1-2")

      // seqNr 1 is always accepted
      offsetStore.isAccepted(envelope1) shouldBe true
      offsetStore.addInflight(envelope1)
      offsetStore.getInflight() shouldBe Map("p1" -> 1L)
      offsetStore.saveOffset(TimestampOffset(startTime.plusMillis(1), Map("p1" -> 1L))).futureValue
      offsetStore.getInflight() shouldBe empty

      // seqNr 2 is accepts since it follows seqNr 1 that is stored in state
      offsetStore.isAccepted(envelope2) shouldBe true
      // simulate envelope processing error by not adding envelope2 to inflight

      // seqNr 3 is not accepted, still waiting for seqNr 2
      offsetStore.isAccepted(envelope3) shouldBe false

      // offer seqNr 2 once again
      offsetStore.isAccepted(envelope2) shouldBe true
      offsetStore.addInflight(envelope2)
      offsetStore.getInflight() shouldBe Map("p1" -> 2L)

      // offer seqNr 3  once more
      offsetStore.isAccepted(envelope3) shouldBe true
      offsetStore.addInflight(envelope3)
      offsetStore.getInflight() shouldBe Map("p1" -> 3L)

      // and they are removed from inflight once they have been stored
      offsetStore.saveOffset(TimestampOffset(startTime.plusMillis(2), Map("p1" -> 3L))).futureValue
      offsetStore.getInflight() shouldBe empty
    }

    "evict old records" in {
      val projectionId = genRandomProjectionId()
      val evictSettings = settings.copy(timeWindow = JDuration.ofSeconds(100), evictInterval = JDuration.ofSeconds(10))
      import evictSettings._
      val offsetStore = createOffsetStore(projectionId, evictSettings)

      val startTime = Instant.now()
      log.debug("Start time [{}]", startTime)

      offsetStore.saveOffset(TimestampOffset(startTime, Map("p1" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(1)), Map("p2" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(2)), Map("p3" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(evictInterval), Map("p4" -> 1L))).futureValue
      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(evictInterval).plus(JDuration.ofSeconds(1)), Map("p4" -> 1L)))
        .futureValue
      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(evictInterval).plus(JDuration.ofSeconds(2)), Map("p5" -> 1L)))
        .futureValue
      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(evictInterval).plus(JDuration.ofSeconds(3)), Map("p6" -> 1L)))
        .futureValue
      offsetStore.getState().size shouldBe 6

      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.minusSeconds(10)), Map("p7" -> 1L))).futureValue
      offsetStore.getState().size shouldBe 7 // nothing evicted yet

      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(timeWindow.plus(evictInterval).minusSeconds(3)), Map("p8" -> 1L)))
        .futureValue
      offsetStore.getState().size shouldBe 8 // still nothing evicted yet

      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(timeWindow.plus(evictInterval).plusSeconds(1)), Map("p8" -> 2L)))
        .futureValue
      offsetStore.getState().byPid.keySet shouldBe Set("p5", "p6", "p7", "p8")

      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(timeWindow.plus(evictInterval).plusSeconds(20)), Map("p8" -> 3L)))
        .futureValue
      offsetStore.getState().byPid.keySet shouldBe Set("p7", "p8")
    }

    "delete old records" in {
      val projectionId = genRandomProjectionId()
      val deleteSettings = settings.copy(timeWindow = JDuration.ofSeconds(100))
      import deleteSettings._
      val offsetStore = createOffsetStore(projectionId, deleteSettings)

      val startTime = Instant.now()
      log.debug("Start time [{}]", startTime)

      offsetStore.saveOffset(TimestampOffset(startTime, Map("p1" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(1)), Map("p2" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(2)), Map("p3" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(3)), Map("p4" -> 1L))).futureValue
      offsetStore.deleteOldTimestampOffsets().futureValue shouldBe 0
      offsetStore.readOffset().futureValue // this will load from database
      offsetStore.getState().size shouldBe 4

      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.minusSeconds(2)), Map("p5" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.minusSeconds(1)), Map("p6" -> 1L))).futureValue
      // nothing deleted yet
      offsetStore.deleteOldTimestampOffsets().futureValue shouldBe 0
      offsetStore.readOffset().futureValue // this will load from database
      offsetStore.getState().size shouldBe 6

      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.plusSeconds(1)), Map("p7" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.plusSeconds(2)), Map("p8" -> 1L))).futureValue
      offsetStore.deleteOldTimestampOffsets().futureValue shouldBe 2
      offsetStore.readOffset().futureValue // this will load from database
      offsetStore.getState().byPid.keySet shouldBe Set("p3", "p4", "p5", "p6", "p7", "p8")
    }

    "periodically delete old records" in {
      val projectionId = genRandomProjectionId()
      val deleteSettings =
        settings.copy(timeWindow = JDuration.ofSeconds(100), deleteInterval = JDuration.ofMillis(500))
      import deleteSettings._
      val offsetStore = createOffsetStore(projectionId, deleteSettings)

      val startTime = Instant.now()
      log.debug("Start time [{}]", startTime)

      offsetStore.saveOffset(TimestampOffset(startTime, Map("p1" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.plusSeconds(1)), Map("p2" -> 1L))).futureValue
      eventually {
        offsetStore.readOffset().futureValue // this will load from database
        offsetStore.getState().byPid.keySet shouldBe Set("p2")
      }

      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(timeWindow.multipliedBy(2).plusSeconds(2)), Map("p3" -> 1L)))
        .futureValue
      eventually {
        offsetStore.readOffset().futureValue // this will load from database
        offsetStore.getState().byPid.keySet shouldBe Set("p3")
      }
    }

    "clear offset" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      offsetStore.saveOffset(3L).futureValue
      offsetStore.readOffset[Long]().futureValue shouldBe Some(3L)

      offsetStore.clearOffset().futureValue
      offsetStore.readOffset[Long]().futureValue shouldBe None
    }

    "read and save paused" in {
      pending // FIXME not implemented yet
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      offsetStore.readManagementState().futureValue shouldBe None

      offsetStore.savePaused(paused = true).futureValue
      offsetStore.readManagementState().futureValue shouldBe Some(ManagementState(paused = true))

      offsetStore.savePaused(paused = false).futureValue
      offsetStore.readManagementState().futureValue shouldBe Some(ManagementState(paused = false))
    }
  }
}
