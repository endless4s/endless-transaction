package endless.transaction.impl.adapter

import endless.transaction.impl.Generators
import endless.transaction.impl.data.TransactionEvent
import org.scalacheck.Prop.forAll
import org.scalacheck.Test

class TransactionEventAdapterSuite extends munit.ScalaCheckSuite with Generators {
  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(200)
      .withMaxDiscardRatio(10)

  property("transaction event to proto and back is the same") {
    val adapter = new TransactionEventAdapter[TID, BID, Q, R]()
    forAll { (event: TransactionEvent[TID, BID, Q, R]) =>
      assertEquals(adapter.fromJournal(adapter.toJournal(event)), event)
    }
  }
}
