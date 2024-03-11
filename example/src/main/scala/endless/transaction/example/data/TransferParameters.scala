package endless.transaction.example.data

import endless.transaction.example.helpers.RetryHelpers.RetryParameters

import scala.concurrent.duration.FiniteDuration

final case class TransferParameters(
    timeout: FiniteDuration,
    branchRetry: TransferParameters.BranchRetryParameters
)

object TransferParameters {
  final case class BranchRetryParameters(
      onError: RetryParameters,
      onPendingTransfer: RetryParameters
  )
}
