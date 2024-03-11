package endless.transaction.example.logic

import cats.Id
import endless.\/
import endless.core.protocol.CommandSender
import endless.transaction.example.Generators
import endless.transaction.example.algebra.Account
import endless.transaction.example.algebra.Account.AlreadyExists
import endless.transaction.example.data.Transfer.TransferID
import endless.transaction.example.data.{AccountID, NonNegAmount, PosAmount, Transfer}
import org.scalacheck.Prop.forAll

class AccountProtocolSuite extends munit.ScalaCheckSuite with Generators {
  val protocol = new AccountProtocol()

  test("open") {
    forAll { (id: AccountID, reply: AlreadyExists.type \/ Unit) =>
      implicit val sender: CommandSender[Id, AccountID] =
        CommandSender.local[Id, AccountID, Account[_[_]]](
          protocol,
          new TestAccount {
            override def open: Id[Account.AlreadyExists.type \/ Unit] = reply
          }
        )
      val actualReply = protocol.clientFor(id).open
      assertEquals(actualReply, reply)
    }
  }

  test("balance") {
    forAll { (id: AccountID, reply: Account.Unknown.type \/ NonNegAmount) =>
      implicit val sender: CommandSender[Id, AccountID] =
        CommandSender.local[Id, AccountID, Account[_[_]]](
          protocol,
          new TestAccount {
            override def balance: Id[Account.Unknown.type \/ NonNegAmount] = reply
          }
        )
      val actualReply = protocol.clientFor(id).balance
      assertEquals(actualReply, reply)
    }
  }

  test("deposit") {
    forAll { (id: AccountID, amount: PosAmount, reply: Account.Unknown.type \/ PosAmount) =>
      implicit val sender: CommandSender[Id, AccountID] =
        CommandSender.local[Id, AccountID, Account[_[_]]](
          protocol,
          new TestAccount {
            override def deposit(amount: PosAmount): Id[Account.Unknown.type \/ PosAmount] = reply
          }
        )
      val actualReply = protocol.clientFor(id).deposit(amount)
      assertEquals(actualReply, reply)
    }
  }

  test("withdraw") {
    forAll { (id: AccountID, amount: PosAmount, reply: Account.WithdrawFailure \/ NonNegAmount) =>
      implicit val sender: CommandSender[Id, AccountID] =
        CommandSender.local[Id, AccountID, Account[_[_]]](
          protocol,
          new TestAccount {
            override def withdraw(amount: PosAmount): Id[Account.WithdrawFailure \/ NonNegAmount] =
              reply
          }
        )
      val actualReply = protocol.clientFor(id).withdraw(amount)
      assertEquals(actualReply, reply)
    }
  }

  test("prepareOutgoingTransfer") {
    forAll {
      (
          id: AccountID,
          transferID: TransferID,
          transfer: Transfer,
          reply: Account.WithdrawFailure \/ Unit
      ) =>
        implicit val sender: CommandSender[Id, AccountID] =
          CommandSender.local[Id, AccountID, Account[_[_]]](
            protocol,
            new TestAccount {
              override def prepareOutgoingTransfer(
                  id: Transfer.TransferID,
                  transfer: Transfer
              ): Id[Account.WithdrawFailure \/ Unit] = reply
            }
          )
        val actualReply = protocol.clientFor(id).prepareOutgoingTransfer(transferID, transfer)
        assertEquals(actualReply, reply)
    }
  }

  test("prepareIncomingTransfer") {
    forAll {
      (
          id: AccountID,
          transferID: TransferID,
          transfer: Transfer,
          reply: Account.Unknown.type \/ Unit
      ) =>
        implicit val sender: CommandSender[Id, AccountID] =
          CommandSender.local[Id, AccountID, Account[_[_]]](
            protocol,
            new TestAccount {
              override def prepareIncomingTransfer(
                  id: Transfer.TransferID,
                  transfer: Transfer
              ): Id[Account.Unknown.type \/ Unit] = reply
            }
          )
        val actualReply = protocol.clientFor(id).prepareIncomingTransfer(transferID, transfer)
        assertEquals(actualReply, reply)
    }
  }

  test("commitTransfer") {
    forAll { (id: AccountID, transferID: TransferID, reply: Account.TransferFailure \/ Unit) =>
      implicit val sender: CommandSender[Id, AccountID] =
        CommandSender.local[Id, AccountID, Account[_[_]]](
          protocol,
          new TestAccount {
            override def commitTransfer(
                id: Transfer.TransferID
            ): Id[Account.TransferFailure \/ Unit] =
              reply
          }
        )
      val actualReply = protocol.clientFor(id).commitTransfer(transferID)
      assertEquals(actualReply, reply)
    }
  }

  test("abortTransfer") {
    forAll { (id: AccountID, transferID: TransferID, reply: Account.TransferFailure \/ Unit) =>
      implicit val sender: CommandSender[Id, AccountID] =
        CommandSender.local[Id, AccountID, Account[_[_]]](
          protocol,
          new TestAccount {
            override def abortTransfer(
                id: Transfer.TransferID
            ): Id[Account.TransferFailure \/ Unit] =
              reply
          }
        )
      val actualReply = protocol.clientFor(id).abortTransfer(transferID)
      assertEquals(actualReply, reply)
    }
  }

  private trait TestAccount extends Account[Id] {
    def open: Id[Account.AlreadyExists.type \/ Unit] = fail("should not be called")

    def balance: Id[Account.Unknown.type \/ NonNegAmount] = fail("should not be called")

    def prepareOutgoingTransfer(
        id: Transfer.TransferID,
        transfer: Transfer
    ): Id[Account.WithdrawFailure \/ Unit] = fail("should not be called")

    def prepareIncomingTransfer(
        id: Transfer.TransferID,
        transfer: Transfer
    ): Id[Account.Unknown.type \/ Unit] = fail("should not be called")

    def commitTransfer(id: Transfer.TransferID): Id[Account.TransferFailure \/ Unit] = fail(
      "should not be called"
    )

    def abortTransfer(id: Transfer.TransferID): Id[Account.TransferFailure \/ Unit] = fail(
      "should not be called"
    )

    def deposit(amount: PosAmount): Id[Account.Unknown.type \/ PosAmount] = fail(
      "should not be called"
    )

    def withdraw(amount: PosAmount): Id[Account.WithdrawFailure \/ NonNegAmount] = fail(
      "should not be called"
    )
  }
}
