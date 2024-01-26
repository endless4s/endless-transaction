package endless.transaction.example.app

import cats.data.EitherT
import cats.effect.{IO, Resource}
import cats.syntax.show.*
import com.comcast.ip4s.Port
import endless.transaction.example.algebra.Account.*
import endless.transaction.example.algebra.Accounts
import endless.transaction.example.data.{AccountID, PosAmount}
import org.http4s.{HttpRoutes, Response}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

class HttpServer(accounts: Accounts[IO]) {
  def transfer(origin: AccountID, destination: AccountID, amount: PosAmount): IO[Response[IO]] =
    EitherT(
      accounts.transfer(origin, destination, amount)
    ).foldF(
      {
        case Accounts.TransferFailure.InsufficientFunds(missing) =>
          BadRequest(show"Account with ID $origin doesn't have sufficient funds, missing $missing")
        case Accounts.TransferFailure.AccountNotFound(accountID) =>
          BadRequest(show"Account with ID $accountID doesn't exist")
        case Accounts.TransferFailure.Timeout => InternalServerError("Transfer timed out")
        case Accounts.TransferFailure.OtherPendingTransfer =>
          BadRequest(show"One of the two accounts already has a pending transfer")
      },
      _ =>
        Ok(show"Transferred $amount from account with ID $origin to account with ID $destination")
    )

  def deposit(id: AccountID, amount: Int): IO[Response[IO]] =
    EitherT(accounts.accountFor(id).deposit(PosAmount(amount))).foldF(
      _ => BadRequest(show"Account with ID $id doesn't exist"),
      newBalance => Ok(show"Deposited $amount to account with ID $id, new balance: $newBalance")
    )

  def withdraw(id: AccountID, amount: Int): IO[Response[IO]] =
    EitherT(accounts.accountFor(id).withdraw(PosAmount(amount))).foldF(
      {
        case InsufficientFunds(missing) =>
          BadRequest(show"Account with ID $id doesn't have sufficient funds, missing $missing")
        case PendingOutgoingTransfer =>
          BadRequest(show"Account with ID $id has a pending outgoing transfer")
        case Unknown => BadRequest(show"Account with ID $id doesn't exist")
      },
      newBalance => Ok(show"Withdrew $amount from account with ID $id, new balance: $newBalance")
    )

  def open(id: AccountID): IO[Response[IO]] = EitherT(accounts.accountFor(id).open).foldF(
    _ => BadRequest(show"Account with ID $id already exists"),
    _ => Ok(show"Account with ID $id opened")
  )

  def balanceFor(id: AccountID): IO[Response[IO]] =
    EitherT(accounts.accountFor(id).balance).foldF(
      _ => BadRequest(show"Account with ID $id doesn't exist"),
      balance => Ok(balance.value.toString)
    )

}

object HttpServer {
  def apply(port: Port, accounts: Accounts[IO]): Resource[IO, Server] =
    Resource
      .pure(new HttpServer(accounts))
      .map(server =>
        HttpRoutes
          .of[IO] {
            case GET -> Root / "account" / id / "balance" => server.balanceFor(AccountID(id))
            case POST -> Root / "account" / id            => server.open(AccountID(id))
            case POST -> Root / "account" / id / "deposit" / IntVar(amount) =>
              server.deposit(AccountID(id), amount)
            case POST -> Root / "account" / id / "withdraw" / IntVar(amount) =>
              server.withdraw(AccountID(id), amount)
            case POST -> Root / "account" / origin / "transfer" / "to" / destination / IntVar(
                  amount
                ) =>
              server.transfer(AccountID(origin), AccountID(destination), PosAmount(amount))
          }
          .orNotFound
      )
      .flatMap(EmberServerBuilder.default[IO].withPort(port).withHttpApp(_).build)
}
