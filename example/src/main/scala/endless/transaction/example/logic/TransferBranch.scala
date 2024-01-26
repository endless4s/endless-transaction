package endless.transaction.example.logic

import cats.MonadError
import cats.data.EitherT
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.show.*
import endless.transaction.Branch
import endless.transaction.example.algebra.Account
import endless.transaction.example.algebra.Account.InsufficientFunds
import endless.transaction.example.algebra.Accounts.TransferFailure
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.data.{AccountID, Transfer}
import org.typelevel.log4cats.Logger

class TransferBranch[F[_]: Logger](accountID: AccountID, account: Account[F])(implicit
    monadError: MonadError[F, Throwable]
) extends Branch[F, TransferID, AccountID, Transfer, TransferFailure] {
  import monadError.*

  def prepare(transferID: TransferID, transfer: Transfer): F[Branch.Vote[TransferFailure]] =
    Logger[F].debug(show"Preparing transfer $transferID: $transfer for account $accountID") >> {
      if (accountID === transfer.origin)
        account.prepareOutgoingTransfer(transferID, transfer).map {
          case Left(Account.Unknown) =>
            Branch.Vote.Abort(TransferFailure.AccountNotFound(accountID))
          case Left(InsufficientFunds(missing)) =>
            Branch.Vote.Abort(TransferFailure.InsufficientFunds(missing))
          case Left(Account.PendingOutgoingTransfer) =>
            Branch.Vote.Abort(TransferFailure.OtherPendingTransfer)
          case Right(_) => Branch.Vote.Commit
        }
      else
        account.prepareIncomingTransfer(transferID, transfer).map {
          case Left(Account.Unknown) =>
            Branch.Vote.Abort(TransferFailure.AccountNotFound(accountID))
          case Left(Account.PendingIncomingTransfer) =>
            Branch.Vote.Abort(TransferFailure.OtherPendingTransfer)
          case Right(_) => Branch.Vote.Commit
        }
    }

  def commit(transferID: TransferID): F[Unit] =
    Logger[F].debug(show"Committing transfer $transferID for account $accountID") >>
      EitherT(account.commitTransfer(transferID))
        .foldF[Unit](
          error => raiseError[Unit](new RuntimeException(error.message)),
          pure
        )

  def abort(transferID: TransferID): F[Unit] =
    Logger[F].debug(show"Aborting transfer $transferID for account $accountID") >>
      EitherT(account.abortTransfer(transferID)).foldF(
        error => raiseError[Unit](new RuntimeException(error.message)),
        pure
      )
}
