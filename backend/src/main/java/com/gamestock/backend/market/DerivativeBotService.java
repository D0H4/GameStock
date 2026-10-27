package com.gamestock.backend.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Small, separately funded population. All executions use the normal derivative risk/ledger path. */
@Service
public class DerivativeBotService {
    private static final Logger log = LoggerFactory.getLogger(DerivativeBotService.class);
    static final long STARTING_CASH = 1_000_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DerivativeService derivatives;
    private final DerivativeMarketImpact impact;
    private final MarketService market;
    private final AdminRuntimeControl controls;
    private Clock clock = Clock.systemUTC();
    private boolean initialized;
    private double actionCredit;
    @Value("${gamestock.derivatives.bots.enabled:true}") private boolean enabled = true;
    @Value("${gamestock.derivatives.bots.count:48}") private int count = 48;

    public DerivativeBotService(JdbcTemplate jdbc, PlatformTransactionManager manager, DerivativeService derivatives,
                                DerivativeMarketImpact impact, MarketService market, AdminRuntimeControl controls) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.derivatives = derivatives;
        this.impact = impact;
        this.market = market;
        this.controls = controls;
    }

    void setClock(Clock clock) { this.clock = clock; }

    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_bot_state (
                  user_id BIGINT PRIMARY KEY,
                  ordinal INT NOT NULL UNIQUE,
                  next_action_at TIMESTAMP(3) NOT NULL,
                  actions BIGINT NOT NULL DEFAULT 0,
                  INDEX ix_derivative_bot_due (next_action_at),
                  CONSTRAINT fk_derivative_bot_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
    }

    void initializeBots() {
        int population = Math.max(0, Math.min(200, count));
        transactions.executeWithoutResult(status -> {
            market.requireMatchingOwner();
            for (int ordinal = 0; ordinal < population; ordinal++) {
                String username = String.format(Locale.ROOT, "derivative_bot_%03d", ordinal + 1);
                jdbc.update("""
                        INSERT IGNORE INTO users(username,password_hash,nickname,cash) VALUES (?,'BOT',?,?)
                        """, username, "선옵 " + DerivativeBotStrategy.style(ordinal).name() + " " + (ordinal + 1), STARTING_CASH);
                // Never adopt an existing human/trader account with a colliding username.
                List<Long> users = jdbc.queryForList("SELECT id FROM users WHERE username=? AND password_hash='BOT'", Long.class, username);
                if (!users.isEmpty()) jdbc.update("""
                        INSERT IGNORE INTO derivative_bot_state(user_id,ordinal,next_action_at) VALUES (?,?,?)
                        """, users.get(0), ordinal, Timestamp.from(clock.instant().plusSeconds(ordinal % 12)));
            }
        });
        initialized = true;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 15000)
    public synchronized void tick() {
        if (!enabled || !market.matchingEnabled() || !market.ownerAvailable() || !market.activeSimulation()) return;
        try {
            var settings = controls.settings();
            if (settings.tradersStopped() || settings.marketPaused()) { actionCredit = 0; return; }
            market.requireMatchingOwner();
            if (!initialized) initializeBots();
            var snapshot = impact.current(clock.instant());
            if (snapshot == null) { actionCredit = 0; return; }
            actionCredit = Math.min(4, actionCredit + 4 * settings.traderShare());
            int limit = (int) actionCredit;
            if (limit == 0) return;
            List<Long> due = jdbc.queryForList("""
                    SELECT user_id FROM derivative_bot_state WHERE next_action_at<=? AND ordinal<?
                    ORDER BY next_action_at,user_id LIMIT ?
                    """, Long.class, Timestamp.from(clock.instant()), Math.max(0, Math.min(200, count)), limit);
            for (long user : due) {
                actionCredit -= 1;
                try { act(user, snapshot); }
                catch (IllegalArgumentException | IllegalStateException expected) {
                    // A price may go stale, an expiry may settle, or a margin check may reject the order.
                    defer(user);
                    log.debug("Derivative bot {} waits: {}", user, expected.getMessage());
                } catch (RuntimeException error) {
                    defer(user);
                    log.warn("Derivative bot {} action rolled back: {}", user, error.toString());
                }
            }
        } catch (RuntimeException error) {
            log.warn("Derivative bot tick deferred: {}", error.toString());
        }
    }

    void act(long user, DerivativeMarketImpact.Snapshot snapshot) {
        Instant now = clock.instant();
        if (snapshot == null || snapshot.constituents().isEmpty() || now.isBefore(snapshot.at())
                || !now.isBefore(snapshot.at().plusSeconds(DerivativeMarketImpact.MAX_AGE_SECONDS))) return;
        transactions.executeWithoutResult(status -> {
            // Same lock order as normal orders; a pause waits for admitted actions to finish.
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT bots_paused OR traders_paused OR market_paused FROM admin_runtime WHERE id=1 FOR SHARE
                    """, Boolean.class))) return;
            market.requireMatchingOwner();
            var states = jdbc.query("""
                    SELECT ordinal,actions FROM derivative_bot_state WHERE user_id=? AND next_action_at<=? FOR UPDATE
                    """, (rs, row) -> new long[]{rs.getInt("ordinal"), rs.getLong("actions")}, user, Timestamp.from(now));
            if (states.isEmpty()) return;
            int ordinal = (int) states.get(0)[0];
            long actions = states.get(0)[1] + 1;
            jdbc.update("UPDATE derivative_bot_state SET actions=?,next_action_at=? WHERE user_id=?", actions,
                    Timestamp.from(now.plusSeconds(DerivativeBotStrategy.delaySeconds(ordinal, actions))), user);
            long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id=? AND password_hash='BOT' FOR UPDATE", Long.class, user);
            var positions = jdbc.query("""
                    SELECT id,kind,side,quantity,entry_price,entry_notional,margin,strike,expiry_at,opened_at
                    FROM derivative_positions WHERE user_id=? AND status='OPEN' ORDER BY id LIMIT 1
                    """, (rs, row) -> new DerivativeBotStrategy.Holding(rs.getLong("id"), rs.getString("kind"),
                    rs.getString("side"), rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
                    rs.getLong("margin"), rs.getDouble("strike"), rs.getTimestamp("expiry_at") == null ? null
                            : rs.getTimestamp("expiry_at").toInstant(), rs.getTimestamp("opened_at").toInstant()), user);
            var holding = positions.isEmpty() ? null : positions.get(0);
            var decision = DerivativeBotStrategy.decide(ordinal, actions, cash, holding, snapshot, now);
            if (decision.close() != null) derivatives.close(user, decision.close(), holding.quantity());
            else if (decision.open() != null) derivatives.open(user, decision.open());
        });
    }

    private void defer(long user) {
        Instant now = clock.instant();
        jdbc.update("UPDATE derivative_bot_state SET next_action_at=? WHERE user_id=? AND next_action_at<=?",
                Timestamp.from(now.plusSeconds(15)), user, Timestamp.from(now));
    }
}
