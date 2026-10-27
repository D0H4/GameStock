package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;

import static com.gamestock.backend.market.UserFeatureModels.*;

/** Persistent per-user/detail-page features shared by web and mobile clients. */
@Service
public class UserFeatureService {
    private final JdbcTemplate jdbc;

    public UserFeatureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void ensureTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_watchlists (
                  user_id BIGINT NOT NULL, stock_id BIGINT NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (user_id, stock_id),
                  CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_watchlist_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS price_alerts (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, stock_id BIGINT NOT NULL,
                  target_price BIGINT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, triggered_at TIMESTAMP NULL,
                  CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_alert_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_alert_user_active (user_id, active)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS stock_comments (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, stock_id BIGINT NOT NULL,
                  comment_text VARCHAR(240) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_comment_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  INDEX ix_comment_stock_time (stock_id, created_at)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS stock_tags (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_id BIGINT NOT NULL, tag VARCHAR(40) NOT NULL,
                  created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uq_stock_tag (stock_id, tag),
                  CONSTRAINT fk_tag_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
                  CONSTRAINT fk_tag_user FOREIGN KEY (created_by) REFERENCES users(id)
                )
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_feature_limits(user_id BIGINT NOT NULL,feature VARCHAR(12) NOT NULL,window_no BIGINT NOT NULL,actions INT NOT NULL,PRIMARY KEY(user_id,feature),FOREIGN KEY(user_id) REFERENCES users(id))");
        for(String[] index:List.of(new String[]{"stock_comments","ix_comment_user_time","user_id,created_at"},new String[]{"stock_tags","ix_tag_user_time","created_by,created_at"}))
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",Integer.class,index[0],index[1])==0)
                jdbc.execute("CREATE INDEX "+index[1]+" ON "+index[0]+" ("+index[2]+")");
    }

    private void reserveWrite(long userId, String feature) {
        // The account lock serializes both quotas and counters across application instances.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,userId);
        jdbc.update("""
                INSERT INTO user_feature_limits(user_id,feature,window_no,actions) VALUES(?,?,UNIX_TIMESTAMP() DIV 60,1)
                ON DUPLICATE KEY UPDATE actions=IF(window_no=VALUES(window_no),actions+1,1),window_no=VALUES(window_no)
                """,userId,feature);
        if(jdbc.queryForObject("SELECT actions FROM user_feature_limits WHERE user_id=? AND feature=?",Integer.class,userId,feature)>10)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"잠시 후 다시 시도해 주세요.");
    }
    private void quota(String sql, long id, int limit, String message) {
        if(jdbc.queryForObject(sql,Integer.class,id)>=limit)throw new IllegalArgumentException(message);
    }

    public void ensureDefaultTags() {
        insertDefault("UMA", "육성"); insertDefault("UMA", "서브컬처"); insertDefault("UMA", "라이브서비스");
        insertDefault("BA", "학원"); insertDefault("BA", "수집형"); insertDefault("BA", "라이브서비스");
        insertDefault("GOV", "RPG"); insertDefault("GOV", "수집형"); insertDefault("GOV", "액션");
        insertDefault("ZZZ", "액션"); insertDefault("ZZZ", "서브컬처"); insertDefault("ZZZ", "라이브서비스");
        insertDefault("GKM", "육성"); insertDefault("GKM", "아이돌"); insertDefault("GKM", "서브컬처");
        insertDefault("KR", "레이싱"); insertDefault("KR", "아이템전");
        insertDefault("MC", "생존"); insertDefault("MC", "샌드박스");
        insertDefault("KD", "레이싱"); insertDefault("KD", "멀티플레이");
        insertDefault("MAPLE", "RPG"); insertDefault("MAPLE", "MMORPG"); insertDefault("MAPLE", "라이브서비스");
    }

    private void insertDefault(String code, String tag) {
        jdbc.update("INSERT IGNORE INTO stock_tags (stock_id, tag) SELECT id, ? FROM stocks WHERE stock_code = ?", tag, code);
    }

    public List<WatchlistEntry> watchlist(long userId) {
        return jdbc.query("SELECT s.stock_code, w.created_at FROM user_watchlists w JOIN stocks s ON s.id = w.stock_id WHERE w.user_id = ? ORDER BY w.created_at DESC",
                (rs, row) -> new WatchlistEntry(rs.getString(1), iso(rs.getTimestamp(2))), userId);
    }

    @Transactional
    public void addWatchlist(long userId, String code) {
        long stockId = stockId(code);
        jdbc.update("INSERT IGNORE INTO user_watchlists (user_id, stock_id) VALUES (?, ?)", userId, stockId);
    }

    @Transactional
    public void removeWatchlist(long userId, String code) {
        jdbc.update("DELETE w FROM user_watchlists w JOIN stocks s ON s.id = w.stock_id WHERE w.user_id = ? AND s.stock_code = ?", userId, normalize(code));
    }

    public List<PriceAlert> alerts(long userId) {
        return jdbc.query("SELECT a.id, s.stock_code, a.target_price, a.active, a.created_at, a.triggered_at FROM price_alerts a JOIN stocks s ON s.id = a.stock_id WHERE a.user_id = ? ORDER BY a.created_at DESC LIMIT 50",
                (rs, row) -> new PriceAlert(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), iso(rs.getTimestamp(5)), iso(rs.getTimestamp(6))), userId);
    }

    @Transactional
    public PriceAlert addAlert(long userId, PriceAlertRequest request) {
        long stockId = stockId(request.stockCode());
        if (request.targetPrice() <= 0) throw new IllegalArgumentException("알림 가격은 1원 이상이어야 합니다.");
        reserveWrite(userId,"ALERT");
        quota("SELECT COUNT(*) FROM price_alerts WHERE user_id=?",userId,200,"알림은 최대 200개까지 보관할 수 있습니다. 이전 알림을 삭제해 주세요.");
        quota("SELECT COUNT(*) FROM price_alerts WHERE user_id=? AND active=TRUE",userId,50,"활성 알림은 최대 50개입니다.");
        jdbc.update("INSERT INTO price_alerts (user_id, stock_id, target_price) VALUES (?, ?, ?)", userId, stockId, request.targetPrice());
        return jdbc.queryForObject("SELECT a.id, s.stock_code, a.target_price, a.active, a.created_at, a.triggered_at FROM price_alerts a JOIN stocks s ON s.id = a.stock_id WHERE a.id = ?",
                (rs, row) -> new PriceAlert(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), iso(rs.getTimestamp(5)), iso(rs.getTimestamp(6))), jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @Transactional
    public void deleteAlert(long userId, long alertId) {
        jdbc.update("DELETE FROM price_alerts WHERE id = ? AND user_id = ?", alertId, userId);
    }

    public List<StockComment> comments(String code) {
        return jdbc.query("SELECT c.id, s.stock_code, u.nickname, c.comment_text, c.created_at FROM stock_comments c JOIN stocks s ON s.id = c.stock_id JOIN users u ON u.id = c.user_id WHERE s.stock_code = ? ORDER BY c.created_at DESC LIMIT 50",
                (rs, row) -> new StockComment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), iso(rs.getTimestamp(5))), normalize(code));
    }

    @Transactional
    public StockComment addComment(long userId, String code, CommentRequest request) {
        String text = request.text() == null ? "" : request.text().trim();
        if (text.length() < 1 || text.length() > 240) throw new IllegalArgumentException("댓글은 1~240자로 입력해 주세요.");
        long stockId = stockId(code);
        reserveWrite(userId,"COMMENT");
        quota("SELECT COUNT(*) FROM stock_comments WHERE user_id=?",userId,1000,"댓글 보관 한도에 도달했습니다.");
        jdbc.update("INSERT INTO stock_comments (user_id, stock_id, comment_text) VALUES (?, ?, ?)", userId, stockId, text);
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return jdbc.queryForObject("SELECT c.id, s.stock_code, u.nickname, c.comment_text, c.created_at FROM stock_comments c JOIN stocks s ON s.id = c.stock_id JOIN users u ON u.id = c.user_id WHERE c.id = ?",
                (rs, row) -> new StockComment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), iso(rs.getTimestamp(5))), id);
    }

    public List<StockTag> tags(String code) {
        return jdbc.query("SELECT s.stock_code, t.tag, t.created_at FROM stock_tags t JOIN stocks s ON s.id = t.stock_id WHERE s.stock_code = ? ORDER BY t.created_at, t.tag LIMIT 100",
                (rs, row) -> new StockTag(rs.getString(1), rs.getString(2), iso(rs.getTimestamp(3))), normalize(code));
    }

    @Transactional
    public StockTag addTag(long userId, String code, TagRequest request) {
        String tag = request.tag() == null ? "" : request.tag().trim().replaceFirst("^#", "");
        if (tag.length() < 1 || tag.length() > 40) throw new IllegalArgumentException("태그는 1~40자로 입력해 주세요.");
        long stockId = stockId(code);
        // Match stock -> account lock order used by matching; concurrent users share the stock quota.
        jdbc.queryForObject("SELECT id FROM stocks WHERE id=? FOR UPDATE",Long.class,stockId);
        reserveWrite(userId,"TAG");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM stock_tags WHERE stock_id=? AND tag=?",Integer.class,stockId,tag)==0) {
            quota("SELECT COUNT(*) FROM stock_tags WHERE created_by=?",userId,100,"계정별 태그는 최대 100개입니다.");
            quota("SELECT COUNT(*) FROM stock_tags WHERE stock_id=?",stockId,100,"종목별 태그는 최대 100개입니다.");
        }
        jdbc.update("INSERT IGNORE INTO stock_tags (stock_id, tag, created_by) VALUES (?, ?, ?)", stockId, tag, userId);
        return jdbc.queryForObject("SELECT s.stock_code, t.tag, t.created_at FROM stock_tags t JOIN stocks s ON s.id = t.stock_id WHERE t.stock_id = ? AND t.tag = ?",
                (rs, row) -> new StockTag(rs.getString(1), rs.getString(2), iso(rs.getTimestamp(3))), stockId, tag);
    }

    @Transactional
    public void deleteUserData(long userId) {
        jdbc.update("DELETE FROM user_feature_limits WHERE user_id=?",userId);
        jdbc.update("DELETE FROM price_alerts WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM stock_comments WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM stock_tags WHERE created_by = ?", userId);
        jdbc.update("DELETE FROM user_watchlists WHERE user_id = ?", userId);
    }

    private long stockId(String code) {
        Long id = jdbc.query("SELECT id FROM stocks WHERE stock_code = ?", (rs, row) -> rs.getLong(1), normalize(code)).stream().findFirst().orElse(null);
        if (id == null) throw new IllegalArgumentException("존재하지 않는 종목입니다.");
        return id;
    }

    private String normalize(String code) { return code == null ? "" : code.trim().toUpperCase(Locale.ROOT); }
    private String iso(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant().toString(); }
}
