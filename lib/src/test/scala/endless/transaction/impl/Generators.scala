package endless.transaction.impl

import cats.data.NonEmptyList
import cats.syntax.eq.*
import endless.core.entity.SideEffect.Trigger
import endless.core.protocol.EntityIDCodec
import endless.transaction.Transaction.AbortReason
import endless.transaction.impl.algebra.TransactionCreator
import endless.transaction.impl.data.{TransactionEvent, TransactionState}
import endless.transaction.{BinaryCodec, Branch, Transaction}
import org.scalacheck.{Arbitrary, Gen, Shrink}

import java.nio.ByteBuffer

trait Generators {
  type TID = String
  type BID = Int
  type Q = Boolean
  type R = Double

  implicit val tidCodec: BinaryCodec[TID] = new BinaryCodec[TID] {
    def encode(a: TID): Array[Byte] = a.getBytes
    def decode(payload: Array[Byte]): TID = new String(payload)
  }
  implicit val entityIDCodec: EntityIDCodec[TID] = EntityIDCodec(identity, identity)
  implicit val bidCodec: BinaryCodec[BID] = new BinaryCodec[BID] {
    def encode(a: BID): Array[Byte] =
      ByteBuffer.allocate(Integer.BYTES).putInt(a).array()
    def decode(payload: Array[Byte]): BID = ByteBuffer.wrap(payload).getInt
  }
  implicit val qCodec: BinaryCodec[Q] = new BinaryCodec[Q] {
    def encode(a: Q): Array[Byte] = Array(if (a) 1.toByte else 0.toByte)
    def decode(payload: Array[Byte]): Q = payload.head === 1.toByte
  }
  implicit val rCodec: BinaryCodec[R] = new BinaryCodec[R] {
    def encode(a: R): Array[Byte] = a.toString.getBytes
    def decode(payload: Array[Byte]): R = new String(payload).toDouble
  }

  implicit def noShrink[A]: Shrink[A] = Shrink.shrinkAny

  val tidGen: Gen[TID] = Gen.alphaNumStr
  val bidGen: Gen[BID] = Gen.posNum[Int]
  val qGen: Gen[Q] = Gen.oneOf(true, false)
  val rGen: Gen[R] = Gen.posNum[Double]

  private def listOfAtLeastTwo[A](gen: Gen[A]): Gen[List[A]] =
    for {
      first <- gen
      second <- gen.suchThat(_ != first)
      rest <- Gen.listOf(gen)
    } yield first :: second :: rest

  val abortReasonGen: Gen[AbortReason[R]] = Gen.oneOf(
    Gen.const(AbortReason.Timeout),
    Gen.option(rGen).map(AbortReason.Client(_)),
    listOfAtLeastTwo(rGen).map(list => AbortReason.Branches(NonEmptyList.fromListUnsafe(list)))
  )

  val voteGen: Gen[Branch.Vote[R]] = Gen.oneOf(
    Gen.const(Branch.Vote.Commit),
    rGen.map(Branch.Vote.Abort(_))
  )

  val preparingGen: Gen[TransactionState.Preparing[TID, BID, Q, R]] =
    for {
      id <- tidGen
      query <- qGen
      branches <- listOfAtLeastTwo(bidGen)
    } yield TransactionState.Preparing(id, branches.map(_ -> None).toMap, query)

  val committingGen: Gen[TransactionState.Committing[TID, BID, Q, R]] =
    for {
      id <- tidGen
      query <- qGen
      branches <- listOfAtLeastTwo(bidGen)
    } yield TransactionState.Committing(id, branches.map(_ -> false).toMap, query)

  val abortingGen: Gen[TransactionState.Aborting[TID, BID, Q, R]] =
    for {
      id <- tidGen
      query <- qGen
      branches <- listOfAtLeastTwo(bidGen)
      reason <- abortReasonGen
    } yield TransactionState.Aborting(id, branches.map(_ -> false).toMap, query, reason)

  val abortedGen: Gen[TransactionState.Aborted[TID, BID, Q, R]] =
    for {
      id <- tidGen
      query <- qGen
      branches <- listOfAtLeastTwo(bidGen)
      reason <- abortReasonGen
    } yield TransactionState.Aborted(id, query, branches.toSet, reason)

  val committedGen: Gen[TransactionState.Committed[TID, BID, Q, R]] =
    for {
      id <- tidGen
      query <- qGen
      branches <- listOfAtLeastTwo(bidGen)
    } yield TransactionState.Committed(id, query, branches.toSet)

  val pendingTransactionStateGen: Gen[TransactionState.Pending[TID, BID, Q, R]] =
    Gen.oneOf(preparingGen, committingGen, abortingGen)

  val failedGen: Gen[TransactionState.Failed[TID, BID, Q, R]] =
    for {
      origin <- pendingTransactionStateGen
      error <- Gen.alphaNumStr
    } yield TransactionState.Failed(origin, List(origin.branches.head -> error).toMap)

  val finalTransactionStateGen: Gen[TransactionState.Final[TID, BID, Q, R]] =
    Gen.oneOf(abortedGen, committedGen, failedGen)

  val transactionStateGen: Gen[TransactionState[TID, BID, Q, R]] =
    Gen.oneOf(preparingGen, committingGen, abortingGen, abortedGen, committedGen, failedGen)

  val transactionStatusGen: Gen[Transaction.Status[R]] = transactionStateGen.map(_.status)

  val finalTransactionStatusGen: Gen[Transaction.Status.Final[R]] =
    finalTransactionStateGen.map(_.status.asInstanceOf[Transaction.Status.Final[R]])

  val pendingTransactionStatusGen: Gen[Transaction.Status.Pending[R]] = pendingTransactionStateGen
    .map(_.status.asInstanceOf[Transaction.Status.Pending[R]])

  val alreadyExistsGen: Gen[TransactionCreator.AlreadyExists.type] =
    Gen.const(TransactionCreator.AlreadyExists)
  val unknownGen: Gen[Transaction.Unknown.type] = Gen.const(Transaction.Unknown)
  val tooLateToAbortGen: Gen[Transaction.TooLateToAbort.type] =
    Gen.const(Transaction.TooLateToAbort)
  val transactionFailedGen: Gen[Transaction.TransactionFailed.type] =
    Gen.const(Transaction.TransactionFailed)

  val abortErrorGen: Gen[Transaction.AbortError] = Gen.oneOf(
    unknownGen,
    tooLateToAbortGen,
    transactionFailedGen
  )

  val persistenceOrRecoveryTriggerGen: Gen[Trigger] =
    Gen.oneOf(Trigger.AfterPersistence, Trigger.AfterRecovery)
  val allTriggersGen: Gen[Trigger] =
    Gen.oneOf(Trigger.AfterRead, Trigger.AfterPersistence, Trigger.AfterRecovery)

  val transactionEventGen: Gen[TransactionEvent[TID, BID, Q, R]] = Gen.oneOf(
    Gen
      .zip(tidGen, qGen, Gen.nonEmptyListOf(bidGen).map(NonEmptyList.fromListUnsafe))
      .map((TransactionEvent.Created.apply[TID, BID, Q] _).tupled),
    Gen.zip(bidGen, voteGen).map((TransactionEvent.BranchVoted.apply[BID, R] _).tupled),
    Gen.option(rGen).map(TransactionEvent.ClientAborted(_)),
    bidGen.map(TransactionEvent.BranchCommitted(_)),
    bidGen.map(TransactionEvent.BranchAborted(_)),
    Gen.zip(bidGen, Gen.alphaNumStr).map((TransactionEvent.BranchFailed.apply[BID] _).tupled),
    Gen.const(TransactionEvent.Timeout)
  )

  implicit val arbAbortReason: Arbitrary[AbortReason[R]] = Arbitrary(abortReasonGen)

  implicit val arbPreparing: Arbitrary[TransactionState.Preparing[TID, BID, Q, R]] = Arbitrary(
    preparingGen
  )
  implicit val arbCommitting: Arbitrary[TransactionState.Committing[TID, BID, Q, R]] = Arbitrary(
    committingGen
  )
  implicit val arbAborting: Arbitrary[TransactionState.Aborting[TID, BID, Q, R]] = Arbitrary(
    abortingGen
  )
  implicit val arbAborted: Arbitrary[TransactionState.Aborted[TID, BID, Q, R]] = Arbitrary(
    abortedGen
  )
  implicit val arbCommitted: Arbitrary[TransactionState.Committed[TID, BID, Q, R]] = Arbitrary(
    committedGen
  )
  implicit val arbFailed: Arbitrary[TransactionState.Failed[TID, BID, Q, R]] = Arbitrary(
    failedGen
  )
  implicit val arbTransactionState: Arbitrary[TransactionState[TID, BID, Q, R]] = Arbitrary(
    transactionStateGen
  )
  implicit val arbPendingTransactionState: Arbitrary[TransactionState.Pending[TID, BID, Q, R]] =
    Arbitrary(pendingTransactionStateGen)
  implicit val arbFinalTransactionState: Arbitrary[TransactionState.Final[TID, BID, Q, R]] =
    Arbitrary(finalTransactionStateGen)
  implicit val arbTransactionStatus: Arbitrary[Transaction.Status[R]] = Arbitrary(
    transactionStatusGen
  )
  implicit val arbFinalTransactionStatus: Arbitrary[Transaction.Status.Final[R]] = Arbitrary(
    finalTransactionStatusGen
  )
  implicit val arbPendingTransactionStatus: Arbitrary[Transaction.Status.Pending[R]] = Arbitrary(
    pendingTransactionStatusGen
  )
  implicit val arbVote: Arbitrary[Branch.Vote[R]] = Arbitrary(voteGen)
  implicit val arbAlreadyExists: Arbitrary[TransactionCreator.AlreadyExists.type] = Arbitrary(
    alreadyExistsGen
  )
  implicit val arbUnknown: Arbitrary[Transaction.Unknown.type] = Arbitrary(unknownGen)
  implicit val arbTooLateToAbort: Arbitrary[Transaction.TooLateToAbort.type] = Arbitrary(
    tooLateToAbortGen
  )
  implicit val arbTransactionFailed: Arbitrary[Transaction.TransactionFailed.type] = Arbitrary(
    transactionFailedGen
  )
  implicit val arbTransactionEvent: Arbitrary[TransactionEvent[TID, BID, Q, R]] = Arbitrary(
    transactionEventGen
  )
  implicit val arbAbortError: Arbitrary[Transaction.AbortError] = Arbitrary(abortErrorGen)
  implicit val arbTID: Arbitrary[TID] = Arbitrary(tidGen)
  implicit val arbBID: Arbitrary[BID] = Arbitrary(bidGen)
  implicit val arbQ: Arbitrary[Q] = Arbitrary(qGen)
  implicit val arbR: Arbitrary[R] = Arbitrary(rGen)
  implicit def arbNonEmptyList[A: Arbitrary]: Arbitrary[NonEmptyList[A]] =
    Arbitrary(
      Gen.nonEmptyListOf(implicitly[Arbitrary[A]].arbitrary).map(NonEmptyList.fromListUnsafe)
    )

}
