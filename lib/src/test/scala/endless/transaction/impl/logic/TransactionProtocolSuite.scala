package endless.transaction.impl.logic

import cats.Id
import cats.data.NonEmptyList
import endless.\/
import endless.core.protocol.CommandSender
import endless.transaction.impl.Generators
import endless.transaction.{Branch, Transaction}
import endless.transaction.impl.algebra.{TransactionAlg, TransactionCreator}
import org.scalacheck.Prop.forAll

class TransactionProtocolSuite extends munit.ScalaCheckSuite with Generators {

  val protocol = new TransactionProtocol[TID, BID, Q, R]()
  test("create") {
    forAll {
      (
          id: TID,
          query: Q,
          branches: NonEmptyList[BID],
          reply: TransactionCreator.AlreadyExists.type \/ Unit
      ) =>
        implicit val sender: CommandSender[Id, TID] =
          CommandSender.local[
            Id,
            TID,
            ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T
          ]( // have to use this verbose type syntax to cross-compile
            protocol,
            new TestTransactionAlg {
              override def create(
                  id: TID,
                  query: Q,
                  branches: NonEmptyList[BID]
              ): Id[TransactionCreator.AlreadyExists.type \/ Unit] = reply
            }
          )
        val actualReply = protocol.clientFor(id).create(id, query, branches)
        assertEquals(actualReply, reply)
    }
  }

  test("query") {
    forAll { (id: TID, reply: Transaction.Unknown.type \/ Q) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def query: Id[Transaction.Unknown.type \/ Q] = reply
          }
        )
      val actualReply = protocol.clientFor(id).query
      assertEquals(actualReply, reply)
    }
  }

  test("branches") {
    forAll { (id: TID, reply: Transaction.Unknown.type \/ Set[BID]) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def branches: Id[Transaction.Unknown.type \/ Set[BID]] = reply
          }
        )
      val actualReply = protocol.clientFor(id).branches
      assertEquals(actualReply, reply)
    }
  }

  test("status") {
    forAll { (id: TID, reply: Transaction.Unknown.type \/ Transaction.Status[R]) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def status: Id[Transaction.Unknown.type \/ Transaction.Status[R]] = reply
          }
        )
      val actualReply = protocol.clientFor(id).status
      assertEquals(actualReply, reply)
    }
  }

  test("abort") {
    forAll { (id: TID, reason: Option[R], reply: Transaction.AbortError \/ Unit) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def abort(reason: Option[R]): Id[Transaction.AbortError \/ Unit] = reply
          }
        )
      val actualReply = protocol.clientFor(id).abort(reason)
      assertEquals(actualReply, reply)
    }
  }

  test("branchVoted") {
    forAll { (id: TID, branch: BID, vote: Branch.Vote[R], reply: Unit) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def branchVoted(branch: BID, vote: Branch.Vote[R]): Id[Unit] = reply
          }
        )
      val actualReply = protocol.clientFor(id).branchVoted(branch, vote)
      assertEquals(actualReply, reply)
    }
  }

  test("branchCommitted") {
    forAll { (id: TID, branch: BID, reply: Unit) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def branchCommitted(branch: BID): Id[Unit] = reply
          }
        )
      val actualReply = protocol.clientFor(id).branchCommitted(branch)
      assertEquals(actualReply, reply)
    }
  }

  test("branchAborted") {
    forAll { (id: TID, branch: BID, reply: Unit) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def branchAborted(branch: BID): Id[Unit] = reply
          }
        )
      val actualReply = protocol.clientFor(id).branchAborted(branch)
      assertEquals(actualReply, reply)
    }
  }

  test("branchFailed") {
    forAll { (id: TID, branch: BID, error: TID, reply: Unit) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def branchFailed(branch: BID, error: TID): Id[Unit] = reply
          }
        )
      val actualReply = protocol.clientFor(id).branchFailed(branch, error)
      assertEquals(actualReply, reply)
    }
  }

  test("timeout") {
    forAll { (id: TID, reply: Unit) =>
      implicit val sender: CommandSender[Id, TID] =
        CommandSender.local[Id, TID, ({ type T[F[_]] = TransactionAlg[F, TID, BID, Q, R] })#T](
          protocol,
          new TestTransactionAlg {
            override def timeout(): Id[Unit] = reply
          }
        )
      val actualReply = protocol.clientFor(id).timeout()
      assertEquals(actualReply, reply)
    }
  }

  private trait TestTransactionAlg extends TransactionAlg[Id, TID, BID, Q, R] {
    def query: Id[Transaction.Unknown.type \/ Q] = fail("should not be called")

    def branches: Id[Transaction.Unknown.type \/ Set[BID]] = fail("should not be called")

    def status: Id[Transaction.Unknown.type \/ Transaction.Status[R]] = fail("should not be called")

    def abort(reason: Option[R]): Id[Transaction.AbortError \/ Unit] = fail("should not be called")

    def branchVoted(branch: BID, vote: Branch.Vote[R]): Id[Unit] = fail("should not be called")

    def branchCommitted(branch: BID): Id[Unit] = fail("should not be called")

    def branchAborted(branch: BID): Id[Unit] = fail("should not be called")

    def branchFailed(branch: BID, error: TID): Id[Unit] = fail("should not be called")

    def timeout(): Id[Unit] = fail("should not be called")

    def create(
        id: TID,
        query: Q,
        branches: NonEmptyList[BID]
    ): Id[TransactionCreator.AlreadyExists.type \/ Unit] = fail("should not be called")

  }
}
