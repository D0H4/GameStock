package com.gamestock.backend.openapi;

import com.gamestock.backend.market.MarketModels.ActiveOrder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** A cursor-based external order view, independent of the UI's five-row preview. */
@Service
public class OpenApiOrderQuery {
    private final JdbcTemplate jdbc;
    public OpenApiOrderQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String SELECT = """
            SELECT o.id, s.stock_code, o.side, o.quantity, o.remaining_quantity, COALESCE(o.price, 0) AS price,
                   o.status, o.order_type, COALESCE(o.reserved_cash, 0) AS reserved_cash,
                   COALESCE(o.reserved_quantity, 0) AS reserved_quantity, o.created_at, o.expires_at
            FROM orders o JOIN stocks s ON s.id=o.stock_id WHERE o.user_id=?
              AND o.created_at > COALESCE((SELECT account_reset_at FROM users WHERE id=o.user_id), '1970-01-01')
            """;
    private static final RowMapper<ActiveOrder> ROW = (rs, row) -> new ActiveOrder(rs.getLong("id"), rs.getString("stock_code"),
            rs.getString("side"), rs.getInt("quantity"), rs.getInt("remaining_quantity"), rs.getLong("price"),
            rs.getString("status"), rs.getString("order_type"), rs.getLong("reserved_cash"), rs.getInt("reserved_quantity"),
            rs.getTimestamp("created_at").toInstant().toString(),
            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant().toString());

    public List<ActiveOrder> open(long userId, String symbol, int limit, Long beforeId) {
        if (limit < 1 || limit > 100 || (beforeId != null && beforeId <= 0))
            throw new IllegalArgumentException("limit는 1~100, beforeId는 양수여야 합니다.");
        return jdbc.query(SELECT + """
                AND o.status='OPEN' AND (? IS NULL OR s.stock_code=?) AND (? IS NULL OR o.id<?)
                ORDER BY o.id DESC LIMIT ?
                """, ROW, userId, symbol, symbol, beforeId, beforeId, limit);
    }

    public ActiveOrder get(long userId, long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("주문 번호를 확인해 주세요.");
        return jdbc.query(SELECT + " AND o.id=?", ROW, userId, orderId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }
}
