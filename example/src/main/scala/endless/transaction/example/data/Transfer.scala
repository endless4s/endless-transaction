package endless.transaction.example.data

import cats.{Eq, Show}
import cats.syntax.show.*
import endless.transaction.{BinaryCodec, StringCodec}
import endless.transaction.example.proto

import java.util.UUID

final case class Transfer(origin: AccountID, destination: AccountID, amount: PosAmount)

object Transfer {
  final case class TransferID(value: UUID) extends AnyVal
  object TransferID {
    def random: TransferID = TransferID(UUID.randomUUID())

    implicit val eq: Eq[TransferID] = Eq.fromUniversalEquals
    implicit val show: cats.Show[TransferID] = cats.Show.show(_.value.show)

    implicit val stringCodec: StringCodec[TransferID] = new StringCodec[TransferID] {
      def encode(id: TransferID): String = id.value.show
      def decode(id: String): TransferID = TransferID(UUID.fromString(id))
    }

    implicit val binaryCodec: BinaryCodec[TransferID] = new BinaryCodec[TransferID] {
      def encode(id: TransferID): Array[Byte] = {
        java.nio.ByteBuffer
          .allocate(16)
          .putLong(id.value.getMostSignificantBits)
          .putLong(id.value.getLeastSignificantBits)
          .array()
      }
      def decode(id: Array[Byte]): TransferID = {
        val buffer = java.nio.ByteBuffer.wrap(id)
        TransferID(new UUID(buffer.getLong, buffer.getLong))
      }
    }
  }

  implicit val binaryCodec: BinaryCodec[Transfer] = new BinaryCodec[Transfer] {
    def decode(payload: Array[Byte]): Transfer = {
      val transfer = proto.model.Transfer.parseFrom(payload)
      Transfer(
        AccountID(transfer.origin.id),
        AccountID(transfer.destination.id),
        PosAmount(transfer.amount)
      )
    }

    def encode(a: Transfer): Array[Byte] = proto.model
      .Transfer(
        proto.model.AccountID(a.origin.value),
        proto.model.AccountID(a.destination.value),
        a.amount.value
      )
      .toByteArray
  }

  implicit val show: Show[Transfer] =
    Show.show(t =>
      show"Transfer from account `${t.origin}` to account `${t.destination}` of ${t.amount}"
    )
}
