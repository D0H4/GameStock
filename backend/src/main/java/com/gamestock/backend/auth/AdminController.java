package com.gamestock.backend.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 관리자 전용 API. 모든 요청은 Firebase 인증 후 ADMIN 역할을 다시 확인한다. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService auth;
    private final AdminService admin;
    private final JdbcTemplate jdbc;

    public AdminController(AuthService auth, AdminService admin, JdbcTemplate jdbc) {
        this.auth = auth;
        this.admin = admin;
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health(@RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        return Map.of(
                "status", "ok",
                "users", count("users"),
                "openOrders", countWhere("orders", "status = 'OPEN'"),
                "trades", count("trades"));
    }

    @GetMapping("/users")
    public List<AdminService.AdminUser> users(@RequestParam(value = "q", required = false) String query,
                                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        return admin.users(query);
    }

    @PutMapping("/users/{userId}/trade-ban")
    public AdminService.AdminUser banTrading(@PathVariable long userId, @RequestBody AdminService.TradeBan request,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        return admin.banTrading(auth.requireAdmin(authorization).id(), userId, request);
    }

    @DeleteMapping("/users/{userId}/trade-ban")
    public AdminService.AdminUser liftTradeBan(@PathVariable long userId,
                                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        return admin.liftTradeBan(auth.requireAdmin(authorization).id(), userId);
    }

    @PutMapping("/users/{userId}/nickname")
    public AdminService.AdminUser forceNickname(@PathVariable long userId, @RequestBody AdminService.NicknameChange request,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        return admin.forceNickname(auth.requireAdmin(authorization).id(), userId, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long countWhere(String table, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Long.class);
    }
}
