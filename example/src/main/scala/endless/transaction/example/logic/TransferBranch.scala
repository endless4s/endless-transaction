package endless.transaction.example.logic

import cats.data.EitherT
import cats.effect.kernel.Temporal
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
import endless.transaction.example.helpers.RetryHelpers.*

class TransferBranch[F[_]: Logger](accountID: AccountID, account: Account[F])(implicit
    temporal: Temporal[F]
) extends Branch[F, TransferID, AccountID, Transfer, TransferFailure] {
  import temporal.*

  def prepare(transferID: TransferID, transfer: Transfer): F[Branch.Vote[TransferFailure]] = {
    if (accountID === transfer.origin)
      Logger[F].debug(
        show"Preparing outgoing transfer $transferID: $transfer for account $accountID"
      ) >>
        account
          .prepareOutgoingTransfer(transferID, transfer)
          .map {
            case Left(Account.Unknown) =>
              Branch.Vote.Abort(TransferFailure.AccountNotFound(accountID))
            case Left(InsufficientFunds(missing)) =>
              Branch.Vote.Abort(TransferFailure.InsufficientFunds(missing))
            case Left(Account.PendingOutgoingTransfer) =>
              Branch.Vote.Abort(TransferFailure.OtherPendingTransfer)
            case Right(_) => Branch.Vote.Commit: Branch.Vote[TransferFailure]
          }
          .retryWithBackoff(
            Logger[F]
              .warn(_)(show"Error preparing outgoing transfer $transferID, retrying in a bit")
          )
    else
      Logger[F].debug(show"Preparing incoming $transferID: $transfer for account $accountID") >>
        account
          .prepareIncomingTransfer(transferID, transfer)
          .map {
            case Left(Account.Unknown) =>
              Branch.Vote.Abort(TransferFailure.AccountNotFound(accountID))
            case Left(Account.PendingIncomingTransfer) =>
              Branch.Vote.Abort(TransferFailure.OtherPendingTransfer)
            case Right(_) => Branch.Vote.Commit: Branch.Vote[TransferFailure]
          }
          .retryWithBackoff(
            Logger[F]
              .warn(_)(show"Error preparing incoming transfer $transferID, retrying in a bit")
          )
  }

  def commit(transferID: TransferID): F[Unit] =
    Logger[F].debug(show"Committing transfer $transferID for account $accountID") >>
      EitherT(
        account
          .commitTransfer(transferID)
          .retryWithBackoff(error =>
            Logger[F]
              .warn(error)(show"Error committing transfer $transferID, retrying in a bit")
          )
      )
        .foldF[Unit](
          error => raiseError[Unit](new RuntimeException(error.message)),
          pure
        )

  def abort(transferID: TransferID): F[Unit] =
    Logger[F].debug(show"Aborting transfer $transferID for account $accountID") >>
      EitherT(
        account
          .abortTransfer(transferID)
          .retryWithBackoff(
            Logger[F].warn(_)(show"Error aborting transfer $transferID, retrying in a bit")
          )
      ).foldF(
        {
          case Account.Unknown =>
            Logger[F].debug(show"Account $accountID is unknown, ignoring abort")
          case _: Account.TransferUnknown =>
            Logger[F].debug(
              show"Branch voted abort of transfer $transferID (balance was not enough), no need for further action"
            )
        },
        pure
      )
}
