package com.gamestock.backend.openapi;

import com.gamestock.backend.market.DerivativeModels;

/** The external contract consistently reports actual cash movement, including fees. */
final class OpenApiAccounts {
    private OpenApiAccounts() { }

    static DerivativeModels.Account account(DerivativeModels.Account account) {
        return new DerivativeModels.Account(account.cash(), account.positionValue(), account.equity(),
                account.unrealizedPnl(), account.realizedPnl(), account.totalFees(), account.positions(),
                account.trades().stream().map(OpenApiAccounts::trade).toList());
    }

    static DerivativeModels.Trade trade(DerivativeModels.Trade trade) {
        return trade; // Shared derivative ledger now reports the actual cash movement, including fees.
    }
}
