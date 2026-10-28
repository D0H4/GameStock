package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 하루 한 줄씩 남기는 순자산 기록. 오늘 줄은 몇 분마다 덮어써서 자정 직전 값이 그날의 마감 값이 된다.
 * 순자산은 대출 잔액을 뺀 값이고, 랭킹·담보비율과 같은 평가식(AccountValuation)을 쓴다.
 */
@Service
public class AssetHistoryService {
    private static final Logger log = LoggerFactory.getLogger(AssetHistoryService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** 기록을 남기는 기간. 화면에서 고를 수 있는 가장 긴 기간과 같다. */
    static final int KEEP_DAYS = 365;
    private static final String HUMAN_FILTER = "u.password_hash NOT IN ('BOT', 'TRADER') AND u.username <> 'demo' AND COALESCE(u.role, 'USER') <> 'ADMIN'";
    private static final String NET_ASSET = "(" + AccountValuation.GROSS_ASSET + ") - u.loan_balance";

    public record AssetPoint(String date, long netAsset) { }

    private final JdbcTemplate jdbc;
    private final MarketService market;
    private Clock clock = Clock.systemUTC();

    public AssetHistoryService(JdbcTemplate jdbc, MarketService market) {
        this.jdbc = jdbc;
        this.market = market;
    }

    void setClock(Clock clock) { this.clock = clock; }

    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS asset_snapshots (
                  user_id BIGINT NOT NULL, snapshot_date DATE NOT NULL, net_asset BIGINT NOT NULL,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (user_id, snapshot_date),
                  CONSTRAINT fk_asset_snapshots_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
    }

    /** 지난 days일의 마감 값과 지금 값. 오늘 줄은 저장된 값 대신 지금 평가액으로 바꿔 돌려준다. */
    public List<AssetPoint> history(long userId, int days) {
        int span = Math.max(1, Math.min(KEEP_DAYS, days));
        LocalDate today = today();
        List<AssetPoint> points = new ArrayList<>(jdbc.query("""
                SELECT snapshot_date, net_asset FROM asset_snapshots
                WHERE user_id = ? AND snapshot_date >= ? AND snapshot_date < ?
                ORDER BY snapshot_date
                """, (rs, row) -> new AssetPoint(rs.getDate(1).toLocalDate().toString(), rs.getLong(2)),
                userId, Date.valueOf(today.minusDays(span - 1L)), Date.valueOf(today)));
        jdbc.query("SELECT " + NET_ASSET + " FROM users u WHERE u.id = ?", rs -> {
            points.add(new AssetPoint(today.toString(), rs.getLong(1)));
        }, userId);
        return points;
    }

    @Scheduled(fixedDelayString = "${gamestock.assets.snapshot-ms:300000}", initialDelayString = "${gamestock.assets.initial-delay-ms:60000}")
    public void snapshot() {
        if (!market.ownerAvailable()) return;
        try {
            LocalDate today = today();
            jdbc.update("INSERT INTO asset_snapshots (user_id, snapshot_date, net_asset) SELECT u.id, ?, " + NET_ASSET
                    + " FROM users u WHERE " + HUMAN_FILTER + " ON DUPLICATE KEY UPDATE net_asset = VALUES(net_asset)", Date.valueOf(today));
            jdbc.update("DELETE FROM asset_snapshots WHERE snapshot_date < ? LIMIT 5000", Date.valueOf(today.minusDays(KEEP_DAYS)));
        } catch (RuntimeException error) {
            log.warn("순자산 기록을 남기지 못했습니다.", error);
        }
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(ZONE)); }
}
