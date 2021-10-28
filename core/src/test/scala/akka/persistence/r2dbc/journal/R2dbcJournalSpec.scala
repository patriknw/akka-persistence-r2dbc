/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.journal

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec
import akka.persistence.r2dbc.TestConfig
import akka.persistence.r2dbc.TestDbLifecycle
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object R2dbcJournalSpec {
  val config = R2dbcJournalSpec.testConfig()

  def configWithMeta =
    ConfigFactory
      .parseString("""akka.persistence.r2dbc.with-meta = true""")
      .withFallback(R2dbcJournalSpec.testConfig())

  def testConfig(): Config = {
    ConfigFactory
      .parseString(s"""
      # allow java serialization when testing
      akka.actor.allow-java-serialization = on
      akka.actor.warn-about-java-serializer-usage = off
      """)
      .withFallback(TestConfig.config)
  }
}

class R2dbcJournalSpec extends JournalSpec(R2dbcJournalSpec.config) with TestDbLifecycle {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()
  override def typedSystem: ActorSystem[_] = system.toTyped
}

/* FIXME meta for replicated event sourcing
class R2dbcJournalWithMetaSpec extends JournalSpec(R2dbcJournalSpec.configWithMeta) with TestDbLifecycle {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()
  protected override def supportsMetadata: CapabilityFlag = CapabilityFlag.on()
  override def typedSystem: ActorSystem[_] = system.toTyped
}
 */
