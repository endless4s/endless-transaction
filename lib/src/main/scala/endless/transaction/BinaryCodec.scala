package endless.transaction

import endless.core.protocol.{Decoder, Encoder}

/** Type class for encoding and decoding entity IDs to and from binary */
trait BinaryCodec[A] extends Encoder[A] with Decoder[A]

object BinaryCodec {
  def apply[A](implicit codec: BinaryCodec[A]): BinaryCodec[A] = codec
}
