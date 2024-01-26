package endless.transaction.example.data

final case class AccountID(value: String) extends AnyVal

object AccountID {
  implicit val show: cats.Show[AccountID] = cats.Show.show(_.value)
  implicit val eq: cats.Eq[AccountID] = cats.Eq.fromUniversalEquals

  implicit val binaryCodec: endless.transaction.BinaryCodec[AccountID] =
    new endless.transaction.BinaryCodec[AccountID] {
      def encode(id: AccountID): Array[Byte] = id.value.getBytes("UTF-8")
      def decode(id: Array[Byte]): AccountID = AccountID(new String(id, "UTF-8"))
    }
}
