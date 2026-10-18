package com.gamestock.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");

    @Test void forcedNicknameClearsTheUsersChangeCooldown() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(7L);
        when(rs.getString("nickname")).thenReturn("나쁜닉네임");
        when(rs.getString("role")).thenReturn("USER");
        // 방금 바꾼 닉네임이라 3일 제한이 걸려 있던 사용자
        when(rs.getTimestamp("nickname_changed_at")).thenReturn(Timestamp.from(NOW.minusSeconds(60)));
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), eq(7L))).thenAnswer(call ->
                List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));

        new AdminService(jdbc, mock(AuthService.class), Clock.fixed(NOW, ZoneOffset.UTC))
                .forceNickname(1L, 7L, new AdminService.NicknameChange("새닉네임"));

        verify(jdbc).update("UPDATE users SET nickname = ?, nickname_changed_at = NULL WHERE id = ?", "새닉네임", 7L);
    }
}
