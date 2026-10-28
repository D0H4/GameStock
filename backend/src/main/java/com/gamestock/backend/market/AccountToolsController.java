package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 내 계좌 도구: 순자산 기록과 손절·익절 자동 매도. 모두 로그인이 필요하다. */
@RestController
@RequestMapping("/api")
public class AccountToolsController {
    private final AssetHistoryService assets;
    private final StopOrderService stops;
    private final AuthService auth;

    public AccountToolsController(AssetHistoryService assets, StopOrderService stops, AuthService auth) {
        this.assets = assets;
        this.stops = stops;
        this.auth = auth;
    }

    @GetMapping("/asset-history")
    public List<AssetHistoryService.AssetPoint> assetHistory(@RequestParam(defaultValue = "30") int days,
                                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        return assets.history(auth.requireUser(authorization).id(), days);
    }

    @GetMapping("/stop-orders")
    public List<StopOrderService.StopOrder> stopOrders(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return stops.list(auth.requireUser(authorization).id());
    }

    @PostMapping("/stop-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public StopOrderService.StopOrder createStopOrder(@RequestBody StopOrderService.StopOrderRequest request,
                                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        return stops.create(auth.requireUser(authorization).id(), request);
    }

    @DeleteMapping("/stop-orders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelStopOrder(@PathVariable long id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        stops.cancel(auth.requireUser(authorization).id(), id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
