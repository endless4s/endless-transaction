package endless.transaction.example.adapter

import endless.transaction.example.Generators
import endless.transaction.example.data.AccountEvent
import org.scalacheck.Prop.forAll
import org.scalacheck.Test

class AccountEventAdapterSuite extends munit.ScalaCheckSuite with Generators {
  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(200)
      .withMaxDiscardRatio(10)

  property("account event to proto and back is the same") {
    val adapter = new AccountEventAdapter()
    forAll { (event: AccountEvent) =>
      assertEquals(adapter.fromJournal(adapter.toJournal(event)), event)
    }
  }
}
