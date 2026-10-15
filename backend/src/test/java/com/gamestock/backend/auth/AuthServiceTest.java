package com.gamestock.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    private AuthService service(JdbcTemplate jdbc, Instant changedAt) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("nickname")).thenReturn("원래닉네임");
        when(rs.getTimestamp("nickname_changed_at")).thenReturn(changedAt == null ? null : Timestamp.from(changedAt));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        return new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void firstChangeIsAvailable() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, null);
        assertTrue(auth.profile(7L).nicknameChangeAvailable());
        assertNull(auth.profile(7L).nicknameChangeAvailableAt());
        auth.updateProfile(7L, new AuthService.ProfileUpdate("새닉네임", ""));
        verify(jdbc).update(contains("nickname_changed_at = ?"), eq("새닉네임"), eq(""), eq(Timestamp.from(NOW)), eq(7L));
    }

    @Test void changeIsBlockedUntilSeventyTwoHoursHaveElapsed() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, NOW.minus(Duration.ofDays(3)).plusSeconds(1));
        assertFalse(auth.profile(7L).nicknameChangeAvailable());
        assertEquals(NOW.plusSeconds(1).toString(), auth.profile(7L).nicknameChangeAvailableAt());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> auth.updateProfile(7L, new AuthService.ProfileUpdate("새닉네임", "")));
        assertEquals(409, error.getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void changeIsAllowedAtExactlySeventyTwoHours() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, NOW.minus(Duration.ofDays(3)));
        assertTrue(auth.profile(7L).nicknameChangeAvailable());
        auth.updateProfile(7L, new AuthService.ProfileUpdate("새닉네임", ""));
        verify(jdbc).update(contains("nickname_changed_at = ?"), eq("새닉네임"), eq(""), eq(Timestamp.from(NOW)), eq(7L));
    }

    @Test void newNicknameMustBeTwoToTenCharacters() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, null);
        assertThrows(IllegalArgumentException.class, () -> auth.updateProfile(7L, new AuthService.ProfileUpdate("가", "")));
        assertThrows(IllegalArgumentException.class, () -> auth.updateProfile(7L, new AuthService.ProfileUpdate("열한글자닉네임입니다요", "")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        auth.updateProfile(7L, new AuthService.ProfileUpdate("열글자닉네임입니다", ""));
        verify(jdbc).update(contains("nickname_changed_at = ?"), eq("열글자닉네임입니다"), eq(""), eq(Timestamp.from(NOW)), eq(7L));
    }

    @Test void unchangedNicknameDoesNotRestartCooldown() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, NOW.minusSeconds(30));
        auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", "/profile-avatars/mint-hood-384.png"));
        verify(jdbc).update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", "/profile-avatars/mint-hood-384.png", 7L);
    }

    private static final AuthService.LoginUser USER = new AuthService.LoginUser(7L, "닉네임", "", "", 0, 0, false);

    @Test void attendanceUsesKoreanDateAfterUtcDayBoundary() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO attendance_rewards"), any(Object[].class))).thenReturn(1);
        // 2026-10-02 15:30 UTC is already 2026-10-03 00:30 in Seoul.
        AuthService auth = new AuthService(jdbc, Clock.fixed(Instant.parse("2026-10-02T15:30:00Z"), ZoneOffset.UTC));

        var user = auth.grantAttendanceReward(USER);

        assertEquals(100_000L, user.attendanceReward());
        verify(jdbc).update(startsWith("INSERT IGNORE INTO attendance_rewards"), eq(7L), eq(java.time.LocalDate.parse("2026-10-03")), eq(1), eq(100_000L));
        verify(jdbc).update("UPDATE users SET cash = cash + ? WHERE id = ?", 100_000L, 7L);
    }

    @Test void concurrentDuplicateAttendanceDoesNotPayTwiceOrFail() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Another request inserted today's row after this one checked; INSERT IGNORE reports 0 rows.
        when(jdbc.update(startsWith("INSERT IGNORE INTO attendance_rewards"), any(Object[].class))).thenReturn(0);
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        var user = assertDoesNotThrow(() -> auth.grantAttendanceReward(USER));

        assertEquals(0L, user.attendanceReward());
        verify(jdbc, never()).update(eq("UPDATE users SET cash = cash + ? WHERE id = ?"), any(Object[].class));
    }

    @Test void databaseFailureIsNotReportedAsExpiredLogin() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        com.google.firebase.auth.FirebaseAuth firebase = mock(com.google.firebase.auth.FirebaseAuth.class);
        com.google.firebase.auth.FirebaseToken token = mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-7");
        when(firebase.verifyIdToken("valid")).thenReturn(token);

        try (var statics = mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            statics.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                    () -> auth.requireUser("Bearer valid"));
        }
    }

    @Test void invalidTokenIsStillUnauthorized() throws Exception {
        AuthService auth = new AuthService(mock(JdbcTemplate.class), Clock.fixed(NOW, ZoneOffset.UTC));
        com.google.firebase.auth.FirebaseAuth firebase = mock(com.google.firebase.auth.FirebaseAuth.class);
        when(firebase.verifyIdToken("bad")).thenThrow(new IllegalArgumentException("bad token"));

        try (var statics = mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            statics.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebase);
            var error = assertThrows(ResponseStatusException.class, () -> auth.requireUser("Bearer bad"));
            assertEquals(401, error.getStatusCode().value());
        }
    }

    @Test void nicknameOnlyUpdatePreservesExistingProfileImage() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("nickname")).thenReturn("원래닉네임");
        when(rs.getString("profile_image_url")).thenReturn("https://lh3.googleusercontent.com/a/provider-photo");
        when(rs.getTimestamp("nickname_changed_at")).thenReturn(null);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", null));

        verify(jdbc).update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", "https://lh3.googleusercontent.com/a/provider-photo", 7L);
    }

    @Test void externalProfileImageIsRejected() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthService auth = service(jdbc, null);

        var error = assertThrows(IllegalArgumentException.class,
                () -> auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", "https://tracker.example/pixel.png")));

        assertTrue(error.getMessage().contains("Google"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void legacyExternalImageIsDroppedOnNicknameOnlyUpdate() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("nickname")).thenReturn("원래닉네임");
        when(rs.getString("profile_image_url")).thenReturn("https://tracker.example/pixel.png");
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        auth.updateProfile(7L, new AuthService.ProfileUpdate("원래닉네임", null));

        verify(jdbc).update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", "", 7L);
    }

    @Test void activeTradeBanBlocksOrdersAndShowsOnProfile() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("nickname")).thenReturn("원래닉네임");
        when(rs.getTimestamp("trade_banned_until")).thenReturn(Timestamp.from(NOW.plus(Duration.ofDays(2))));
        when(rs.getTimestamp(1)).thenReturn(Timestamp.from(NOW.plus(Duration.ofDays(2))));
        when(rs.getString(2)).thenReturn("부계정 의심");
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0));
        when(jdbc.query(startsWith("SELECT trade_banned_until"), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                java.util.List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(NOW.plus(Duration.ofDays(2)).toString(), auth.profile(7L).tradeBannedUntil());
        var error = assertThrows(ResponseStatusException.class, () -> auth.requireTradingAllowed(7L));
        assertEquals(403, error.getStatusCode().value());
        assertTrue(error.getReason().contains("부계정 의심"));
    }

    @Test void expiredTradeBanAllowsOrders() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp(1)).thenReturn(Timestamp.from(NOW.minusSeconds(1)));
        when(jdbc.query(startsWith("SELECT trade_banned_until"), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                java.util.List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
        AuthService auth = new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        assertDoesNotThrow(() -> auth.requireTradingAllowed(7L));
    }

    @Test void adminCashIsRefilledToTheCeilingWithoutOpenOrders() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("SUM(reserved_cash)"), eq(Long.class), eq(7L))).thenReturn(0L);
        new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC)).refillAdminCash(7L);
        verify(jdbc).update("UPDATE users SET cash = ? WHERE id = ? AND cash <> ?", AuthService.ADMIN_CASH, 7L, AuthService.ADMIN_CASH);
        assertTrue(AuthService.ADMIN_CASH < (1L << 53), "must stay exact as a JavaScript number");
    }

    @Test void adminCashCountsReservedCashSoCancellingCannotExceedTheCeiling() {
        // 예약금 300조가 묶여 있으면 현금은 700조까지만 채운다. 취소해 환불받아도 합은 1000조다.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        long reserved = 300_000_000_000_000L;
        when(jdbc.queryForObject(contains("SUM(reserved_cash)"), eq(Long.class), eq(7L))).thenReturn(reserved);
        new AuthService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC)).refillAdminCash(7L);
        long target = AuthService.ADMIN_CASH - reserved;
        verify(jdbc).update("UPDATE users SET cash = ? WHERE id = ? AND cash <> ?", target, 7L, target);
    }
}
