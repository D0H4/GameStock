package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static com.gamestock.backend.market.DerivativeModels.*;

/** ETF·선물·옵션 공개 시세와 로그인 사용자 주문. */
@RestController
@RequestMapping("/api/derivatives")
public class DerivativeController {
    private final DerivativeService derivatives;
    private final AuthService auth;

    public DerivativeController(DerivativeService derivatives, AuthService auth) {
        this.derivatives = derivatives;
        this.auth = auth;
    }

    @GetMapping
    public DerivativeSnapshot snapshot() {
        return derivatives.snapshot();
    }

    @GetMapping("/options/flow")
    public OptionFlow optionFlow(@RequestParam(defaultValue = "1d") String range,
                                 @RequestParam(defaultValue = "5m") String interval) {
        return derivatives.optionFlow(range, interval);
    }

    @GetMapping("/futures/chart")
    public java.util.List<IndexCandle> futureChart(@RequestParam(defaultValue = "1h") String range) {
        return derivatives.futureChart(range);
    }

    @GetMapping("/account")
    public Account account(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return derivatives.account(auth.requireUser(authorization).id());
    }

    @PostMapping("/positions")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResult open(@Valid @RequestBody OrderRequest request,
                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthService.LoginUser user = auth.requireUser(authorization);
        if ("ADMIN".equalsIgnoreCase(user.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정은 파생상품을 거래할 수 없습니다.");
        return derivatives.open(user.id(), request);
    }

    @PostMapping("/positions/{positionId}/close")
    public OrderResult close(@PathVariable long positionId,
                             @Valid @RequestBody CloseRequest request,
                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthService.LoginUser user = auth.requireUser(authorization);
        if ("ADMIN".equalsIgnoreCase(user.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정은 파생상품을 거래할 수 없습니다.");
        return derivatives.close(user.id(), positionId, request.quantity());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
