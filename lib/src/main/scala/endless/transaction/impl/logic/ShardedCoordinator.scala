package endless.transaction.impl.logic

import cats.data.NonEmptyList
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.show.*
import cats.{MonadError, Show}
import endless.core.entity.Sharding
import endless.core.protocol.EntityIDCodec
import endless.transaction.impl.algebra.TransactionAlg
import endless.transaction.{Coordinator, Transaction}
import org.typelevel.log4cats.Logger

private[transaction] final class ShardedCoordinator[F[_]: Logger, TID, BID, Q, R](
    sharding: Sharding[F, TID, ({ type T[G[_]] = TransactionAlg[G, TID, BID, Q, R] })#T]
)(implicit entityIDCodec: EntityIDCodec[TID], monadError: MonadError[F, Throwable])
    extends Coordinator[F, TID, BID, Q, R] {
  private implicit val entityIDShow: Show[TID] = Show.show(entityIDCodec.encode)

  def get(id: TID): Transaction[F, BID, Q, R] = sharding.entityFor(id)

  def create(
      id: TID,
      query: Q,
      branch: BID,
      otherBranches: BID*
  ): F[Transaction[F, BID, Q, R]] =
    Logger[F].debug(show"Creating transaction $id") >>
      sharding
        .entityFor(id)
        .create(id, query, NonEmptyList.of[BID](branch, otherBranches*))
        .flatMap {
          case Right(_) =>
            Logger[F].debug(show"Transaction transaction $id successfully created") >> get(id).pure
          case Left(_) =>
            Logger[F].warn(
              show"Transaction $id already exists"
            ) >> (new Coordinator.TransactionAlreadyExists).raiseError
        }

}
