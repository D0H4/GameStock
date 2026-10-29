package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * Keeps the free 50 GB database (and its 50 GB backup quota) from filling with history nobody reads.
 *
 * <ul>
 * <li>stock_price_history is thinned to the first, last, highest and lowest point of each minute after
 *     two days and of each hour after eight days, then dropped after the one-year chart window. Candle
 *     buckets for those ranges are 2 hours or longer, so every chart keeps the same OHLC.</li>
 * <li>Trades, settlements and orders made only between bots are deleted after 30 days once the
 *     liquidity and metric projections have consumed them. Anything a person traded is kept.</li>
 * <li>trading_restriction_events, which nothing reads back, keeps 90 days.</li>
 * <li>Stock discussion threads are deleted, with their replies and likes, once the first post is
 *     {@link StockTalkService#RETENTION} old.</li>
 * </ul>
 *
 * Each pass deletes a bounded number of rows in short autocommit statements so it never holds locks
 * the matching batches need, and resumes from where it stopped on the next pass.
 */
@Component
public class DataRetentionService {
    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);
    static final Duration RAW_HISTORY = Duration.ofDays(2);
    static final Duration MINUTE_HISTORY = Duration.ofDays(8);
    static final Duration HISTORY_LIMIT = Duration.ofDays(400);
    static final Duration BOT_LEDGER = Duration.ofDays(30);
    static final Duration RESTRICTION_LOG = Duration.ofDays(90);
    private static final long SLICE_SECONDS = 3600;
    private static final int ROW_BUDGET = 20_000;
    private static final int SLICE_BUDGET = 400;
    private static final int SCAN_SIZE = 5_000;
    private static final int DELETE_CHUNK = 1_000;

    private final JdbcTemplate jdbc;
    private final MarketService market;
    private final TransactionTemplate transaction;
    private Clock clock = Clock.systemUTC();
    private volatile boolean prepared;
    // In-memory scan positions. A restart rescans from the start once, in bounded steps.
    private long tradeCursor, orderCursor;

    public DataRetentionService(JdbcTemplate jdbc, MarketService market) {
        this.jdbc = jdbc;
        this.market = market;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Scheduled(fixedDelayString = "${gamestock.retention.interval-ms:120000}", initialDelayString = "${gamestock.retention.initial-delay-ms:300000}")
    public void scheduledPrune() {
        if (!market.matchingEnabled() || !market.ownerAvailable()) return;
        try {
            prune();
        } catch (DataAccessException error) {
            // Best effort: a lock timeout or a busy database only delays cleanup to the next pass.
            log.warn("Data retention pass deferred ({})", error.getClass().getSimpleName());
        }
    }

    /** One bounded pass. Returns the number of rows deleted. */
    int prune() {
        prepare();
        long now = clock.millis();
        int[] budget = {ROW_BUDGET};
        int history = thinPriceHistory(now, budget);
        int ledger = budget[0] > 0 ? deleteBotLedger(now, budget) : 0;
        int orders = budget[0] > 0 ? deleteBotOrders(now, budget) : 0;
        int restrictions = budget[0] > 0 ? limited(budget, jdbc.update(
                "DELETE FROM trading_restriction_events WHERE ends_at<? ORDER BY ends_at LIMIT ?",
                new Timestamp(now - RESTRICTION_LOG.toMillis()), Math.min(DELETE_CHUNK, budget[0]))) : 0;
        int talk = budget[0] > 0 ? deleteOldTalk(now, budget) : 0;
        int total = history + ledger + orders + restrictions + talk;
        if (total > 0) log.info("Data retention removed {} price points, {} bot trades, {} bot orders, {} restriction logs, {} discussion posts",
                history, ledger, orders, restrictions, talk);
        return total;
    }

    private void prepare() {
        if (prepared) return;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS price_history_compaction (
                  stock_id BIGINT PRIMARY KEY,
                  minute_until TIMESTAMP NULL,
                  hour_until TIMESTAMP NULL
                )
                """);
        StockTalkService.ensureTables(jdbc);
        prepared = true;
    }

    private int thinPriceHistory(long now, int[] budget) {
        int deleted = 0;
        // Empty hours cost a lookup each, so a pass also stops after this many slices.
        int[] slices = {SLICE_BUDGET};
        jdbc.update("INSERT IGNORE INTO price_history_compaction(stock_id) SELECT id FROM stocks");
        Timestamp expired = new Timestamp(now - HISTORY_LIMIT.toMillis());
        for (long stock : jdbc.queryForList("SELECT id FROM stocks ORDER BY id", Long.class)) {
            if (budget[0] <= 0) break;
            deleted += limited(budget, jdbc.update(
                    "DELETE FROM stock_price_history WHERE stock_id=? AND recorded_at<? ORDER BY recorded_at LIMIT ?",
                    stock, expired, Math.min(DELETE_CHUNK, budget[0])));
            // Hours first. The minute pass starts where the hour pass ends, so old rows are thinned once.
            long hourLimit = now - MINUTE_HISTORY.toMillis();
            deleted += thin(stock, "hour_until", 3600, 0, hourLimit, budget, slices);
            deleted += thin(stock, "minute_until", 60, hourLimit, now - RAW_HISTORY.toMillis(), budget, slices);
        }
        return deleted;
    }

    /** Keeps the first, last, highest and lowest point per bucket, one hour-aligned slice at a time. */
    private int thin(long stock, String cursorColumn, long bucketSeconds, long fromMillis, long limitMillis, int[] budget, int[] slices) {
        long limit = Math.floorDiv(limitMillis / 1000, SLICE_SECONDS) * SLICE_SECONDS;
        long from = Math.floorDiv(fromMillis / 1000, SLICE_SECONDS) * SLICE_SECONDS;
        Timestamp stored = jdbc.queryForObject("SELECT " + cursorColumn + " FROM price_history_compaction WHERE stock_id=?", Timestamp.class, stock);
        Timestamp cursor = new Timestamp(Math.max(from * 1000, stored == null ? 0 : stored.getTime()));
        int deleted = 0;
        while (budget[0] > 0 && slices[0]-- > 0) {
            Timestamp next = jdbc.queryForObject(
                    "SELECT MIN(recorded_at) FROM stock_price_history WHERE stock_id=? AND recorded_at>=? AND recorded_at<?",
                    Timestamp.class, stock, cursor, new Timestamp(limit * 1000));
            if (next == null) {
                jdbc.update("UPDATE price_history_compaction SET " + cursorColumn + "=? WHERE stock_id=?", new Timestamp(limit * 1000), stock);
                break;
            }
            long start = Math.floorDiv(next.getTime() / 1000, SLICE_SECONDS) * SLICE_SECONDS;
            long end = start + SLICE_SECONDS;
            List<Long> redundant = jdbc.queryForList("""
                    SELECT id FROM (
                      SELECT id,
                             ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY recorded_at, id) first_rank,
                             ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY recorded_at DESC, id DESC) last_rank,
                             ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY price DESC, id) high_rank,
                             ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY price, id) low_rank
                      FROM (
                        SELECT id, price, recorded_at, FLOOR(UNIX_TIMESTAMP(recorded_at)/?) bucket
                        FROM stock_price_history WHERE stock_id=? AND recorded_at>=? AND recorded_at<?
                      ) points
                    ) ranked
                    WHERE first_rank>1 AND last_rank>1 AND high_rank>1 AND low_rank>1
                    """, Long.class, bucketSeconds, stock, new Timestamp(start * 1000), new Timestamp(end * 1000));
            int removed = deleteIds("stock_price_history", redundant, budget);
            deleted += removed;
            if (removed < redundant.size()) break; // Out of budget; this slice resumes next pass.
            cursor = new Timestamp(end * 1000);
            jdbc.update("UPDATE price_history_compaction SET " + cursorColumn + "=? WHERE stock_id=?", cursor, stock);
        }
        return deleted;
    }

    /**
     * Bot-only fills older than BOT_LEDGER whose id the liquidity cash projection and the price
     * metric cursor have already passed. Those projections sum trades incrementally from their cursor,
     * so rows behind it no longer contribute to anything still computed.
     */
    private int deleteBotLedger(long now, int[] budget) {
        Set<Long> bots = botAccounts();
        Map<Long, Long> projected = new HashMap<>();
        jdbc.query("""
                SELECT p.stock_id, LEAST(p.last_id, COALESCE(m.last_id, 0))
                FROM lp_cash_projection p LEFT JOIN market_metric_cursors m ON m.stock_id = p.stock_id
                """, rs -> { projected.put(rs.getLong(1), rs.getLong(2)); });
        long cutoff = now - BOT_LEDGER.toMillis();
        List<Long> candidates = new ArrayList<>();
        long[] scanned = {tradeCursor};
        boolean[] reachedRecent = {false};
        jdbc.query("SELECT id, stock_id, buyer_id, seller_id, created_at FROM trades WHERE id>? ORDER BY id LIMIT ?", rs -> {
            if (reachedRecent[0]) return;
            if (rs.getTimestamp(5).getTime() >= cutoff) { reachedRecent[0] = true; return; }
            long id = rs.getLong(1);
            scanned[0] = id;
            if (bots.contains(rs.getLong(3)) && bots.contains(rs.getLong(4)) && id <= projected.getOrDefault(rs.getLong(2), 0L))
                candidates.add(id);
        }, tradeCursor, SCAN_SIZE);
        int deleted = 0;
        for (int start = 0; start < candidates.size() && budget[0] > 0; start += DELETE_CHUNK) {
            List<Long> chunk = candidates.subList(start, Math.min(candidates.size(), start + DELETE_CHUNK));
            String ids = String.join(",", Collections.nCopies(chunk.size(), "?"));
            Integer removed = transaction.execute(status -> {
                // Bot fills settle immediately; a PENDING settlement keeps its trade.
                jdbc.update("DELETE FROM settlements WHERE status<>'PENDING' AND trade_id IN (" + ids + ")", chunk.toArray());
                return jdbc.update("DELETE FROM trades WHERE id IN (" + ids + ") AND NOT EXISTS (SELECT 1 FROM settlements s WHERE s.trade_id=trades.id)", chunk.toArray());
            });
            deleted += limited(budget, removed == null ? 0 : removed);
            if (budget[0] <= 0 && start + DELETE_CHUNK < candidates.size()) return deleted; // Rescan the rest next pass.
        }
        tradeCursor = scanned[0];
        return deleted;
    }

    /** Finished bot orders older than BOT_LEDGER that no remaining trade references. */
    private int deleteBotOrders(long now, int[] budget) {
        Set<Long> bots = botAccounts();
        long cutoff = now - BOT_LEDGER.toMillis();
        List<Long> finished = new ArrayList<>();
        long[] scanned = {orderCursor};
        boolean[] reachedRecent = {false};
        jdbc.query("SELECT id, user_id, status, created_at FROM orders WHERE id>? ORDER BY id LIMIT ?", rs -> {
            if (reachedRecent[0]) return;
            if (rs.getTimestamp(4).getTime() >= cutoff) { reachedRecent[0] = true; return; }
            scanned[0] = rs.getLong(1);
            if (bots.contains(rs.getLong(2)) && !"OPEN".equals(rs.getString(3))) finished.add(rs.getLong(1));
        }, orderCursor, SCAN_SIZE);
        int deleted = 0;
        for (int start = 0; start < finished.size() && budget[0] > 0; start += DELETE_CHUNK) {
            List<Long> chunk = finished.subList(start, Math.min(finished.size(), start + DELETE_CHUNK));
            String ids = String.join(",", Collections.nCopies(chunk.size(), "?"));
            deleted += limited(budget, jdbc.update("DELETE FROM orders WHERE status<>'OPEN' AND id IN (" + ids + ")"
                    + " AND NOT EXISTS (SELECT 1 FROM trades t WHERE t.buy_order_id=orders.id)"
                    + " AND NOT EXISTS (SELECT 1 FROM trades t WHERE t.sell_order_id=orders.id)", chunk.toArray()));
            if (budget[0] <= 0 && start + DELETE_CHUNK < finished.size()) return deleted;
        }
        orderCursor = scanned[0];
        return deleted;
    }

    private Set<Long> botAccounts() {
        return new HashSet<>(jdbc.queryForList("SELECT id FROM users WHERE password_hash IN ('BOT','TRADER')", Long.class));
    }

    /** Whole threads go at once, replies first, so a reply never outlives its post. Likes cascade. */
    int deleteOldTalk(long now, int[] budget) {
        List<Long> roots = jdbc.queryForList("SELECT id FROM stock_talk_posts WHERE parent_id IS NULL AND created_at<? ORDER BY id LIMIT ?",
                Long.class, new Timestamp(now - StockTalkService.RETENTION.toMillis()), Math.min(DELETE_CHUNK, budget[0]));
        if (roots.isEmpty()) return 0;
        int replies = limited(budget, jdbc.update("DELETE FROM stock_talk_posts WHERE parent_id IN ("
                + String.join(",", Collections.nCopies(roots.size(), "?")) + ")", roots.toArray()));
        return replies + deleteIds("stock_talk_posts", roots, budget);
    }

    private int deleteIds(String table, List<Long> ids, int[] budget) {
        int deleted = 0;
        for (int start = 0; start < ids.size() && budget[0] > 0; ) {
            List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + Math.min(DELETE_CHUNK, budget[0])));
            deleted += limited(budget, jdbc.update("DELETE FROM " + table + " WHERE id IN ("
                    + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")", chunk.toArray()));
            start += chunk.size();
        }
        return deleted;
    }

    private static int limited(int[] budget, int deleted) {
        budget[0] -= deleted;
        return deleted;
    }
}
