package endless.transaction.example

import cats.data.NonEmptyList
import endless.transaction.example.algebra.{Account, Accounts}
import endless.transaction.example.data.{
  AccountEvent,
  AccountID,
  AccountState,
  NonNegAmount,
  PosAmount,
  Transfer
}
import org.scalacheck.{Arbitrary, Gen}

trait Generators {
  val nonNegAmountGen: Gen[NonNegAmount] =
    Gen.oneOf(Gen.const(0), Gen.posNum[Int]).map(NonNegAmount(_))

  val posAmountGen: Gen[PosAmount] = Gen.posNum[Int].map(PosAmount(_))

  val accountIDGen: Gen[AccountID] = Gen.alphaStr.suchThat(_.nonEmpty).map(AccountID(_))

  val transferGen: Gen[Transfer] = for {
    from <- accountIDGen
    to <- accountIDGen.suchThat(_ != from)
    amount <- posAmountGen
  } yield Transfer(from, to, amount)

  val transferIDGen: Gen[Transfer.TransferID] = Gen.uuid.map(Transfer.TransferID(_))

  val pendingOutgoingTransferGen: Gen[AccountState.PendingTransfer.Outgoing] = for {
    id <- transferIDGen
    amount <- posAmountGen
  } yield AccountState.PendingTransfer.Outgoing(id, amount)

  val pendingIncomingTransferGen: Gen[AccountState.PendingTransfer.Incoming] = for {
    id <- transferIDGen
    amount <- posAmountGen
  } yield AccountState.PendingTransfer.Incoming(id, amount)

  val pendingSingleOutgoingTransferGen: Gen[AccountState.PendingTransfers.SingleOutgoing] =
    pendingOutgoingTransferGen.map(AccountState.PendingTransfers.SingleOutgoing)

  val pendingAtLeastOneIncomingTransferGen: Gen[AccountState.PendingTransfers.AtLeastOneIncoming] =
    for {
      transfers <- Gen.nonEmptyListOf(pendingIncomingTransferGen)
    } yield AccountState.PendingTransfers.AtLeastOneIncoming(NonEmptyList.fromListUnsafe(transfers))

  val pendingTransfersGen: Gen[AccountState.PendingTransfers] = Gen.oneOf(
    pendingSingleOutgoingTransferGen,
    pendingAtLeastOneIncomingTransferGen
  )

  val accountStateWithoutPendingTransferGen: Gen[AccountState] = for {
    balance <- nonNegAmountGen
  } yield AccountState(balance, None)

  val accountStateWithPendingTransferGen: Gen[AccountState] = for {
    balance <- nonNegAmountGen
    pendingTransfers <- pendingTransfersGen.suchThat {
      case AccountState.PendingTransfers.SingleOutgoing(
            AccountState.PendingTransfer.Outgoing(_, amount)
          ) =>
        balance >= amount
      case _ => true
    }
  } yield AccountState(balance, Some(pendingTransfers))

  val accountStateWithPendingOutgoingTransferGen: Gen[AccountState] = for {
    balance <- nonNegAmountGen
    pendingTransfers <- pendingSingleOutgoingTransferGen.suchThat {
      case AccountState.PendingTransfers.SingleOutgoing(
            AccountState.PendingTransfer.Outgoing(_, amount)
          ) =>
        balance >= amount
    }
  } yield AccountState(balance, Some(pendingTransfers))

  val accountStateGen: Gen[AccountState] = Gen.oneOf(
    accountStateWithoutPendingTransferGen,
    accountStateWithPendingTransferGen
  )

  val emptyAccountStateGen: Gen[AccountState] = Gen.const(AccountState(NonNegAmount(0), None))

  val accountEventGen: Gen[AccountEvent] = Gen.oneOf(
    Gen.const(AccountEvent.Opened),
    posAmountGen.map(AccountEvent.Deposited.apply),
    posAmountGen.map(AccountEvent.Withdrawn.apply),
    transferIDGen.flatMap { id =>
      Gen.oneOf(
        posAmountGen.map(AccountEvent.OutgoingTransferPrepared(id, _)),
        posAmountGen.map(AccountEvent.IncomingTransferPrepared(id, _)),
        Gen.const(AccountEvent.TransferCommitted(id)),
        Gen.const(AccountEvent.TransferAborted(id))
      )
    }
  )

  implicit val arbPosAmount: Arbitrary[PosAmount] = Arbitrary(posAmountGen)
  implicit val arbNonNegAmount: Arbitrary[NonNegAmount] = Arbitrary(nonNegAmountGen)
  implicit val accountState: Arbitrary[AccountState] = Arbitrary(accountStateGen)
  implicit val arbTransferID: Arbitrary[Transfer.TransferID] = Arbitrary(transferIDGen)
  implicit val arbAccountID: Arbitrary[AccountID] = Arbitrary(accountIDGen)
  implicit val arbAlreadyExists: Arbitrary[Account.AlreadyExists.type] = Arbitrary(
    Gen.const(Account.AlreadyExists)
  )
  implicit val arbUnknown: Arbitrary[Account.Unknown.type] = Arbitrary(Gen.const(Account.Unknown))
  implicit val arbInsufficientFunds: Arbitrary[Account.InsufficientFunds] = Arbitrary(
    posAmountGen.map(Account.InsufficientFunds.apply)
  )
  implicit val arbWithdrawFailure: Arbitrary[Account.WithdrawFailure] = Arbitrary(
    Gen.oneOf(
      arbUnknown.arbitrary,
      arbInsufficientFunds.arbitrary,
      Gen.const(Account.PendingOutgoingTransfer)
    )
  )
  implicit val arbTransferFailure: Arbitrary[Account.TransferFailure] = Arbitrary(
    Gen.oneOf(arbUnknown.arbitrary, transferIDGen.map(Account.TransferUnknown.apply))
  )
  implicit val arbTransferFailureAccounts: Arbitrary[Accounts.TransferFailure] = Arbitrary(
    Gen.oneOf(
      accountIDGen.map(Accounts.TransferFailure.AccountNotFound.apply),
      Gen.const(Accounts.TransferFailure.Timeout),
      Gen.const(Accounts.TransferFailure.OtherPendingTransfer),
      posAmountGen.map(Accounts.TransferFailure.InsufficientFunds.apply)
    )
  )
  implicit val arbTransfer: Arbitrary[Transfer] = Arbitrary(transferGen)
  implicit def arbNonEmptyList[A: Arbitrary]: Arbitrary[NonEmptyList[A]] =
    Arbitrary(
      Gen.nonEmptyListOf(implicitly[Arbitrary[A]].arbitrary).map(NonEmptyList.fromListUnsafe)
    )
  implicit val arbAccountEvent: Arbitrary[AccountEvent] = Arbitrary(accountEventGen)
}
