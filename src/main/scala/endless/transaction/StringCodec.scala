package endless.transaction

import endless.core.protocol.EntityIDCodec

/** Type class for encoding and decoding entity IDs to and from strings */
trait StringCodec[A] extends EntityIDCodec[A] {
  def encode(id: A): String
  def decode(id: String): A
}

object StringCodec {
  def apply[A](implicit codec: StringCodec[A]): StringCodec[A] = codec
}
