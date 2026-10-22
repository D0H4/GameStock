package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

/** One last-mark valuation for collateral, rankings and season snapshots (users alias: u). */
final class AccountValuation {
    private AccountValuation() { }
    static final String STOCK_ASSET = """
            COALESCE((SELECT SUM(p.quantity * COALESCE(pm.mark_price, s.current_price))
                FROM portfolios p JOIN stocks s ON s.id = p.stock_id
                LEFT JOIN market_price_metrics pm ON pm.stock_id = s.id
                WHERE p.user_id = u.id AND p.quantity > 0), 0)
            """;
    // Long options and futures are limited-loss contracts: a negative mark is not an extra debt.
    static final String DERIVATIVE_ASSET = "COALESCE((SELECT SUM(GREATEST(dp.mark_value,0)) FROM derivative_positions dp WHERE dp.user_id=u.id AND dp.status='OPEN'),0)";
    static final String GROSS_ASSET = """
            u.cash
            + COALESCE((SELECT SUM(o.reserved_cash) FROM orders o WHERE o.user_id=u.id AND o.status='OPEN'),0)
            + COALESCE((SELECT SUM(st.gross_amount-st.seller_fee) FROM settlements st WHERE st.seller_id=u.id AND st.status='PENDING'),0)
            + """ + STOCK_ASSET + " + " + DERIVATIVE_ASSET;

    static long derivatives(JdbcTemplate jdbc, long userId) {
        return jdbc.queryForObject("SELECT " + DERIVATIVE_ASSET + " FROM users u WHERE u.id=?", Long.class, userId);
    }
}
