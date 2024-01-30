package endless.transaction.impl.logic

import cats.data.NonEmptyList
import cats.effect.kernel.Resource
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

  def get(id: TID): Resource[F, Transaction[F, BID, Q, R]] =
    Resource.make(sharding.entityFor(id).pure[F])(release =
      transaction =>
        (transaction.status >>= {
          case Right(_: Transaction.Status.Final[R]) => ().pure
          case Right(_: Transaction.Status.Pending[R]) =>
            Logger[F].debug(show"Aborting transaction $id") >> transaction.abort() >>= {
              case Right(_) => Logger[F].debug(show"Transaction $id aborted")
              case Left(_)  => Logger[F].warn(show"Failed to abort transaction $id")
            }
          case Left(_) => Logger[F].debug(show"Transaction $id not yet created")
        }).handleErrorWith(throwable =>
          Logger[F].warn(throwable)(show"Failed to abort transaction $id")
        )
    )

  def create(
      id: TID,
      query: Q,
      branch1: BID,
      branch2: BID,
      others: BID*
  ): Resource[F, Transaction[F, BID, Q, R]] =
    Resource.eval(
      Logger[F].debug(show"Creating transaction $id") >>
        sharding
          .entityFor(id)
          .create(id, query, NonEmptyList.of[BID](branch1, (branch2 +: others)*))
    )
      >>= {
        case Right(_) =>
          Resource.eval(
            Logger[F].debug(show"Transaction transaction $id successfully created")
          ) >> get(id)
        case Left(_) =>
          Resource.eval(Logger[F].warn(show"Transaction $id already exists")) >> Resource
            .raiseError[F, Transaction[F, BID, Q, R], Throwable](
              Coordinator.TransactionAlreadyExists
            )
      }

}
