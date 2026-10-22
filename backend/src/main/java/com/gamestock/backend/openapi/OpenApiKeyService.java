package com.gamestock.backend.openapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.gamestock.backend.openapi.OpenApiModels.*;

@Service
public class OpenApiKeyService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public OpenApiKeyService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    OpenApiKeyService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional
    public IssuedKey create(long userId, CreateKey request) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
        int active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM open_api_keys
                WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?
                """, Integer.class, userId, Timestamp.from(clock.instant()));
        if (active >= 10) throw new ResponseStatusException(HttpStatus.CONFLICT, "활성 API 키는 최대 10개입니다.");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "gs_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiry = now.plus(request.expiresInDays() == null ? 90 : request.expiresInDays(), ChronoUnit.DAYS);
        String id = UUID.randomUUID().toString();
        String prefix = token.substring(0, 11);
        jdbc.update("""
                INSERT INTO open_api_keys (id, user_id, name, key_prefix, token_hash, permission, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, request.name().trim(), prefix, hash(token), request.permission().name(),
                Timestamp.from(now), Timestamp.from(expiry));
        return new IssuedKey(new KeyInfo(id, request.name().trim(), prefix, request.permission(), now.toString(), expiry.toString(), null), token);
    }

    public List<KeyInfo> list(long userId) {
        return jdbc.query("""
                SELECT id, name, key_prefix, permission, created_at, expires_at, revoked_at
                FROM open_api_keys WHERE user_id = ?
                ORDER BY (revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP) DESC, created_at DESC LIMIT 100
                """, (rs, row) -> info(rs), userId);
    }

    public void revoke(long userId, String id) {
        int changed = jdbc.update("""
                UPDATE open_api_keys SET revoked_at = COALESCE(revoked_at, ?)
                WHERE id = ? AND user_id = ?
                """, Timestamp.from(clock.instant()), id, userId);
        if (changed == 0 && jdbc.queryForObject("SELECT COUNT(*) FROM open_api_keys WHERE id = ? AND user_id = ?", Integer.class, id, userId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API 키를 찾을 수 없습니다.");
    }

    public Principal authenticate(String authorization, boolean trade) {
        if (authorization == null || !authorization.matches("Bearer gs_[A-Za-z0-9_-]{43}")) throw unauthorized();
        return lookup("k.token_hash", hash(authorization.substring(7)), trade);
    }

    /** Recheck revocation, suspension and role inside the queued write, before any execution. */
    public void requireTrading(Principal principal) {
        Principal current = lookup("k.id", principal.keyId(), true);
        if (current.userId() != principal.userId()) throw unauthorized();
    }

    private Principal lookup(String column, String value, boolean trade) {
        var rows = jdbc.query("""
                SELECT k.id, k.user_id, k.permission, k.expires_at, k.revoked_at, u.role, u.trade_banned_until
                FROM open_api_keys k JOIN users u ON u.id = k.user_id WHERE %s = ?
                """.formatted(column), (rs, row) -> new KeyState(rs.getString("id"), rs.getLong("user_id"),
                Permission.valueOf(rs.getString("permission")), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("revoked_at")), rs.getString("role"), instant(rs.getTimestamp("trade_banned_until"))), value);
        if (rows.isEmpty()) throw unauthorized();
        KeyState key = rows.get(0);
        if (key.revokedAt() != null || !key.expiresAt().isAfter(clock.instant())) throw unauthorized();
        if (key.bannedUntil() != null && key.bannedUntil().isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이용이 정지된 계정입니다.");
        if (trade && (key.permission() != Permission.TRADE || "ADMIN".equalsIgnoreCase(key.role())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 API 키 또는 계정에는 거래 권한이 없습니다.");
        return new Principal(key.id(), key.userId(), key.permission());
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효한 GameStock API 키가 필요합니다.");
    }
    private static KeyInfo info(ResultSet rs) throws SQLException {
        return new KeyInfo(rs.getString("id"), rs.getString("name"), rs.getString("key_prefix"),
                Permission.valueOf(rs.getString("permission")), rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("expires_at").toInstant().toString(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant().toString());
    }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private record KeyState(String id, long userId, Permission permission, Instant expiresAt,
                            Instant revokedAt, String role, Instant bannedUntil) { }
}
