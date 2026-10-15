package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 대출 API. Firebase 로그인 토큰이 있어야 하므로 서버 안에서 도는 봇은 호출할 수 없다. */
@RestController
@RequestMapping("/api/loan")
public class LoanController {
    private final LoanService loans;
    private final AuthService auth;

    public LoanController(LoanService loans, AuthService auth) {
        this.loans = loans;
        this.auth = auth;
    }

    @GetMapping
    public LoanService.LoanStatus status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return loans.status(auth.requireUser(authorization).id());
    }

    @PostMapping("/borrow")
    public LoanService.LoanActionResult borrow(@RequestBody LoanService.LoanRequest request,
                                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        return loans.borrow(auth.requireUser(authorization).id(), request == null ? null : request.amount());
    }

    @PostMapping("/repay")
    public LoanService.LoanActionResult repay(@RequestBody LoanService.LoanRequest request,
                                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        return loans.repay(auth.requireUser(authorization).id(), request == null ? null : request.amount());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
