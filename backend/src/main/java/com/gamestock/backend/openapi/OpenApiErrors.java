package com.gamestock.backend.openapi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

@RestControllerAdvice(basePackages = "com.gamestock.backend.openapi")
public class OpenApiErrors {
    @ExceptionHandler(Exception.class)
    ResponseEntity<OpenApiModels.ApiError> handle(Exception exception) {
        Throwable error = exception;
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        int status = error instanceof ResponseStatusException response ? response.getStatusCode().value()
                : error instanceof IllegalArgumentException || error instanceof org.springframework.http.converter.HttpMessageNotReadableException
                    || error instanceof org.springframework.beans.TypeMismatchException ? 400
                : error instanceof ErrorResponse response ? response.getStatusCode().value() : 500;
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 503 -> "UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "HTTP_" + status;
        };
        String message = error instanceof ResponseStatusException response ? response.getReason()
                : error instanceof IllegalArgumentException ? error.getMessage()
                : status < 500 ? "요청 형식과 필수 필드를 확인해 주세요."
                : "요청을 처리하지 못했습니다. 주문 요청은 같은 Idempotency-Key로 결과를 확인해 주세요.";
        if (status >= 500) org.slf4j.LoggerFactory.getLogger(getClass()).error("OpenAPI request failed", error);
        var builder = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == HttpStatus.UNAUTHORIZED.value()) builder.header("WWW-Authenticate", "Bearer");
        if (error instanceof OpenApiRateLimiter.RateLimited limited) builder.header("Retry-After", Long.toString(limited.retryAfter));
        return builder.body(new OpenApiModels.ApiError(code, message == null ? code : message));
    }
}
