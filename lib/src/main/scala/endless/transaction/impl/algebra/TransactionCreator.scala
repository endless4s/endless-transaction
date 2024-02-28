package endless.transaction.impl.algebra

import cats.data.NonEmptyList
import endless.\/
import endless.transaction.impl.algebra.TransactionCreator.AlreadyExists

private[transaction] trait TransactionCreator[F[_], TID, BID, Q] {
  def create(id: TID, query: Q, branches: NonEmptyList[BID]): F[AlreadyExists.type \/ Unit]
}

private[transaction] object TransactionCreator {
  object AlreadyExists
}
