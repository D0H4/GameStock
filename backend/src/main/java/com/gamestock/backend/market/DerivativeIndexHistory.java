package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;

/** Persistent index bases and immutable expiry fixings; daily protection references are separate. */
final class DerivativeIndexHistory {
    private final JdbcTemplate jdbc;
    DerivativeIndexHistory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_index_bases (
                  stock_id BIGINT PRIMARY KEY, reference_price BIGINT NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  CONSTRAINT fk_derivative_base_stock FOREIGN KEY(stock_id) REFERENCES stocks(id))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_index_observations (
                  observed_at TIMESTAMP(3) PRIMARY KEY, index_value DOUBLE NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_expiry_fixings (
                  expiry_at TIMESTAMP(3) PRIMARY KEY, index_value DOUBLE NOT NULL,
                  observed_at TIMESTAMP(3) NOT NULL)
                """);
    }

    void initializeBases() {
        // Preserve the quote on upgrade; freeze this reference once, never at each midnight.
        jdbc.update("""
                INSERT IGNORE INTO derivative_index_bases(stock_id,reference_price)
                SELECT s.id,GREATEST(1,COALESCE(p.day_reference,s.current_price)) FROM stocks s
                LEFT JOIN stock_protection_state p ON p.stock_id=s.id
                WHERE NOT EXISTS (SELECT 1 FROM derivative_index_bases b WHERE b.stock_id=s.id)
                """);
    }

    void observe(double index, Instant at) {
        // Same timestamp is immutable across readers/instances. No public GET writes samples.
        jdbc.update("INSERT IGNORE INTO derivative_index_observations(observed_at,index_value) VALUES(?,?)", Timestamp.from(at), index);
    }

    double fixing(Instant expiry) {
        Timestamp at = Timestamp.from(expiry);
        var existing = jdbc.queryForList("SELECT index_value FROM derivative_expiry_fixings WHERE expiry_at=?", Double.class, at);
        if (!existing.isEmpty()) return existing.get(0);
        jdbc.update("""
                INSERT IGNORE INTO derivative_expiry_fixings(expiry_at,index_value,observed_at)
                SELECT ?,index_value,observed_at FROM derivative_index_observations
                WHERE observed_at<=? ORDER BY observed_at DESC LIMIT 1
                """, at, at);
        var fixed = jdbc.queryForList("SELECT index_value FROM derivative_expiry_fixings WHERE expiry_at=?", Double.class, at);
        if (fixed.isEmpty()) throw new IllegalStateException("만기 이전 지수 기록이 없어 정산을 보류합니다: " + expiry);
        return fixed.get(0);
    }

    void retain(Instant now) {
        Instant cutoff=now.minusSeconds(30L*86400);
        var pending=jdbc.queryForList("SELECT MIN(expiry_at) FROM derivative_positions WHERE status='OPEN' AND expiry_at IS NOT NULL",Timestamp.class);
        if(!pending.isEmpty() && pending.get(0)!=null && pending.get(0).toInstant().isBefore(cutoff))cutoff=pending.get(0).toInstant();
        Timestamp keep=jdbc.queryForObject("SELECT MAX(observed_at) FROM derivative_index_observations WHERE observed_at<=?",Timestamp.class,Timestamp.from(cutoff));
        if(keep!=null)jdbc.update("DELETE FROM derivative_index_observations WHERE observed_at<? ORDER BY observed_at LIMIT 5000",keep);
        jdbc.update("""
                DELETE FROM derivative_expiry_fixings WHERE expiry_at<?
                  AND NOT EXISTS (SELECT 1 FROM derivative_positions p WHERE p.status='OPEN' AND p.expiry_at=derivative_expiry_fixings.expiry_at)
                ORDER BY expiry_at LIMIT 5000
                """,Timestamp.from(now.minusSeconds(90L*86400)));
    }
}
