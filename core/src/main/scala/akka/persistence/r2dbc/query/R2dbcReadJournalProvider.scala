/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.query

import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

final class R2dbcReadJournalProvider(system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends ReadJournalProvider {
  override def scaladslReadJournal(): scaladsl.R2dbcReadJournal =
    new scaladsl.R2dbcReadJournal(system, config, cfgPath)

  override def javadslReadJournal() = new javadsl.R2dbcReadJournal(scaladslReadJournal())
}
