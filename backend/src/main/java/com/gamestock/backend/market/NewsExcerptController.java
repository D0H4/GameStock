package com.gamestock.backend.market;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class NewsExcerptController {
    private final NewsExcerptService excerpts;

    public NewsExcerptController(NewsExcerptService excerpts) { this.excerpts = excerpts; }

    /** 속보 본문 패널에 붙일 원문 첫 두 문단. 저장된 소식의 Google News 링크만 받는다. */
    @GetMapping("/news/excerpt")
    public NewsExcerptService.Excerpt excerpt(@RequestParam String link) { return excerpts.excerpt(link); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> unknown(NoSuchElementException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }
}
