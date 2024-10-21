package endless.transaction.example.logic

import cats.data.EitherT
import cats.effect.kernel.{Resource, Temporal}
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.show.*
import endless.\/
import endless.core.entity.Sharding
import endless.transaction.Transaction.{AbortReason, Status}
import endless.transaction.example.algebra.Accounts.TransferFailure
import endless.transaction.example.algebra.{Account, Accounts}
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.data.{AccountID, PosAmount, Transfer, TransferParameters}
import endless.transaction.{Coordinator, Transactor}
import org.typelevel.log4cats.Logger

final class ShardedAccounts[F[_]: Temporal: Logger](
    sharding: Sharding[F, AccountID, Account],
    coordinator: Coordinator[F, TransferID, AccountID, Transfer, TransferFailure]
) extends Accounts[F] {

  def accountFor(id: AccountID): Account[F] = sharding.entityFor(id)

  // #transfer
  def transfer(from: AccountID, to: AccountID, amount: PosAmount): F[TransferFailure \/ Unit] =
    coordinator
      .create(TransferID.random, Transfer(from, to, amount), from, to)
      .asResource
      .use(_.pollForFinalStatus())
      .flatMap {
        case Status.Committed => ().asRight[TransferFailure].pure
        case Status.Aborted(reason) =>
          reason match {
            case AbortReason.Timeout =>
              EitherT.leftT(TransferFailure.Timeout: TransferFailure).value
            case AbortReason.Branches(reasons)    => EitherT.leftT(reasons.head).value
            case AbortReason.Client(Some(reason)) => EitherT.leftT(reason).value
            case AbortReason.Client(None) =>
              new Exception("Transaction aborted by client without justification")
                .raiseError[F, TransferFailure \/ Unit]
          }
        case Status.Failed(errors) =>
          Logger[F].error(show"Transaction failed: $errors") *> new Exception(
            "Transaction failed due to branch error"
          ).raiseError[F, TransferFailure \/ Unit]
      }
  // #transfer

}

object ShardedAccounts {
  def transfersCoordinator[F[_]: Temporal: Logger](sharding: Sharding[F, AccountID, Account])(
      implicit
      transferParameters: TransferParameters,
      transactor: Transactor[F]
  ): Resource[F, Coordinator[F, TransferID, AccountID, Transfer, TransferFailure]] = {
    implicit val branchRetryParameters: TransferParameters.BranchRetryParameters =
      transferParameters.branchRetry

    // #create-coordinator
    transactor.coordinator[TransferID, AccountID, Transfer, TransferFailure](
      "transfer",
      { accountID =>
        val account = sharding.entityFor(accountID)
        new TransferBranch(accountID, account)
      },
      Some(transferParameters.timeout)
    )
    // #create-coordinator
  }
}
