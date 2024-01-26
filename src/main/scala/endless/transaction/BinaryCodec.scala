package endless.transaction

import endless.core.protocol.{Decoder, Encoder}

trait BinaryCodec[A] extends Encoder[A] with Decoder[A]

object BinaryCodec {
  def apply[A](implicit codec: BinaryCodec[A]): BinaryCodec[A] = codec
}
