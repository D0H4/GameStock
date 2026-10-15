package com.gamestock.backend.auth;

import com.gamestock.backend.market.OrderService;
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
    private final OrderService orders;

    public AdminController(AuthService auth, AdminService admin, JdbcTemplate jdbc, OrderService orders) {
        this.auth = auth;
        this.admin = admin;
        this.jdbc = jdbc;
        this.orders = orders;
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
        admin.banTrading(auth.requireAdmin(authorization).id(), userId, request);
        // 정지된 계정의 미체결 주문이 정지 중에 체결되지 않도록 모두 취소한다. 정지
        // 트랜잭션이 사용자 행을 잠근 채로 취소를 기다리면 교착되므로 커밋 뒤에 낸다.
        for (long orderId : jdbc.queryForList("SELECT id FROM orders WHERE user_id = ? AND status = 'OPEN'", Long.class, userId)) {
            try {
                orders.cancel(userId, orderId).join();
            } catch (RuntimeException ignored) {
                // 그사이 체결·만료된 주문이면 넘어간다.
            }
        }
        return admin.user(userId);
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
