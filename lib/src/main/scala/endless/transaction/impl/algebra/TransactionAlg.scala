package endless.transaction.impl.algebra

import endless.transaction.Transaction

private[transaction] trait TransactionAlg[F[_], TID, BID, Q, R]
    extends TransactionNotifier[F, BID, Q, R]
    with TransactionCreator[F, TID, BID, Q]
    with Transaction[F, BID, Q, R]
