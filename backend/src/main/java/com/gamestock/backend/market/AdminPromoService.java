package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.gamestock.backend.market.AdminSupport.*;

/** Promotion codes an admin creates on the admin screen; users redeem them through TitleService. */
@Service
public class AdminPromoService {
    static final long MAX_REWARD_CASH = 100_000_000L;
    private final JdbcTemplate db;
    private final AdminSupport support;
    public AdminPromoService(JdbcTemplate db, AdminSupport support) { this.db = db; this.support = support; }

    public record Promo(String code, long rewardCash, List<String> titleIds, Integer maxUses,
                        Instant startsAt, Instant endsAt, boolean active, String memo, String reason) { }

    /** Every code with how often it was used, plus the titles a code may grant. */
    public Map<String, Object> promos() {
        var codes = db.queryForList("""
            SELECT p.*, (SELECT COUNT(*) FROM promo_redemptions r WHERE r.code = p.code) AS used_count,
                   (SELECT MAX(r.redeemed_at) FROM promo_redemptions r WHERE r.code = p.code) AS last_used_at
            FROM promo_codes p ORDER BY p.created_at DESC, p.code
            """);
        var titles = TitleService.TITLES.stream().filter(title -> !title.weekly())
            .map(title -> Map.of("id", title.id(), "name", title.name(), "category", title.category())).toList();
        return Map.of("codes", codes, "titles", titles);
    }

    /** Recent redemptions of one code, newest first. */
    public List<Map<String, Object>> redemptions(String code) {
        return db.queryForList("SELECT r.user_id, u.nickname, r.reward_cash, r.title_ids, r.redeemed_at FROM promo_redemptions r JOIN users u ON u.id = r.user_id WHERE r.code = ? ORDER BY r.id DESC LIMIT 50", key(code));
    }

    @Transactional
    public Map<String, Object> create(long actor, Promo request) {
        String code = key(request.code());
        String titles = validate(request);
        if (!db.queryForList("SELECT code FROM promo_codes WHERE code = ? FOR UPDATE", code).isEmpty())
            throw new IllegalArgumentException("이미 있는 코드입니다. 목록에서 골라 고쳐 주세요.");
        db.update("INSERT INTO promo_codes(code,reward_cash,title_ids,max_uses,starts_at,ends_at,active,memo,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
            code, request.rewardCash(), titles, request.maxUses(), timestamp(request.startsAt()), timestamp(request.endsAt()),
            request.active(), memo(request.memo()), actor);
        var after = existing(code);
        support.audit(actor, "PROMO_CREATE", code, request.reason(), Map.of(), after);
        return after;
    }

    /** The code itself never changes: past redemptions point at it. */
    @Transactional
    public Map<String, Object> update(long actor, String code, Promo request) {
        String key = key(code);
        String titles = validate(request);
        var before = db.queryForList("SELECT * FROM promo_codes WHERE code = ? FOR UPDATE", key);
        if (before.isEmpty()) throw new IllegalArgumentException("프로모션 코드를 찾을 수 없습니다.");
        db.update("UPDATE promo_codes SET reward_cash=?,title_ids=?,max_uses=?,starts_at=?,ends_at=?,active=?,memo=?,updated_at=CURRENT_TIMESTAMP(3) WHERE code=?",
            request.rewardCash(), titles, request.maxUses(), timestamp(request.startsAt()), timestamp(request.endsAt()),
            request.active(), memo(request.memo()), key);
        var after = existing(key);
        support.audit(actor, "PROMO_UPDATE", key, request.reason(), before.get(0), after);
        return after;
    }

    /** Lower-cased code; users type it in any case. */
    static String key(String code) {
        String value = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_-]{2,40}"))
            throw new IllegalArgumentException("코드는 영문·숫자와 _ - 로 2~40자입니다.");
        return value;
    }

    /** Checks the reward and returns the stored title list. */
    private static String validate(Promo request) {
        reason(request.reason());
        if (request.rewardCash() < 0 || request.rewardCash() > MAX_REWARD_CASH)
            throw new IllegalArgumentException("현금 보상은 0원~1억원입니다.");
        List<String> titles = request.titleIds() == null ? List.of() : request.titleIds().stream().distinct().toList();
        for (String id : titles) {
            var title = TitleService.definition(id);
            if (title == null || title.weekly()) throw new IllegalArgumentException("줄 수 없는 칭호입니다: " + id);
        }
        if (request.rewardCash() == 0 && titles.isEmpty())
            throw new IllegalArgumentException("현금이나 칭호 중 하나는 보상으로 정해 주세요.");
        if (request.maxUses() != null && (request.maxUses() < 1 || request.maxUses() > 1_000_000))
            throw new IllegalArgumentException("사용 인원 제한은 1~1,000,000명입니다. 제한이 없으면 비워 두세요.");
        if (request.startsAt() != null && request.endsAt() != null && !request.endsAt().isAfter(request.startsAt()))
            throw new IllegalArgumentException("끝나는 시각은 시작 시각 이후여야 합니다.");
        return String.join(",", titles);
    }

    private static String memo(String value) {
        String memo = value == null ? "" : value.trim();
        if (memo.length() > 200) throw new IllegalArgumentException("메모는 200자 이내입니다.");
        return memo;
    }

    private Map<String, Object> existing(String code) {
        return db.queryForList("SELECT p.*, (SELECT COUNT(*) FROM promo_redemptions r WHERE r.code = p.code) AS used_count FROM promo_codes p WHERE p.code = ?", code).get(0);
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
