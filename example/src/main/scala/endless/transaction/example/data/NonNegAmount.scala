package endless.transaction.example.data

import cats.Show

final case class NonNegAmount(value: Int) {
  def -(amount: PosAmount): NonNegAmount = NonNegAmount(value - amount.value)
  def +(amount: PosAmount): PosAmount = PosAmount(value + amount.value)
  def >=(amount: PosAmount): Boolean = value >= amount.value

  require(value >= 0, "Amount must be non-negative")
}

object NonNegAmount {
  implicit val show: Show[NonNegAmount] = Show.show(_.value.toString)
}
