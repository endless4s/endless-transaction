package endless.transaction.example.data

import scala.concurrent.duration.FiniteDuration

final case class TransferParameters(timeout: FiniteDuration)
