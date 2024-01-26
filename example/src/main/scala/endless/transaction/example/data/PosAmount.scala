package endless.transaction.example.data

import cats.Show

import scala.language.implicitConversions

final case class PosAmount(value: Int) {
  def -(other: NonNegAmount): PosAmount = PosAmount(value - other.value)
  require(value > 0, "Amount must be positive")
}

object PosAmount {
  @SuppressWarnings(Array("org.wartremover.warts.ImplicitConversion"))
  implicit def toNonNegAmount(posAmount: PosAmount): NonNegAmount = NonNegAmount(posAmount.value)

  implicit val show: Show[PosAmount] = Show.show(_.value.toString)
}
