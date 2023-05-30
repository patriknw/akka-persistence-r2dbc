/*
 * Copyright (C) 2022 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.internal.postgres

import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.persistence.r2dbc.R2dbcSettings
import io.r2dbc.spi._

import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 */
@InternalApi
private[r2dbc] final class YugabyteDurableStateDao(settings: R2dbcSettings, connectionFactory: ConnectionFactory)(
    implicit
    ec: ExecutionContext,
    system: ActorSystem[_])
    extends PostgresDurableStateDao(settings, connectionFactory) {

  override protected def sliceCondition(minSlice: Int, maxSlice: Int): String = {
    s"slice BETWEEN $minSlice AND $maxSlice"
  }

}
