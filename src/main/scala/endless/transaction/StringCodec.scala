package endless.transaction

import endless.core.protocol.EntityIDCodec

trait StringCodec[A] extends EntityIDCodec[A] {
  def encode(id: A): String
  def decode(id: String): A
}

object StringCodec {
  def apply[A](implicit codec: StringCodec[A]): StringCodec[A] = codec
}
