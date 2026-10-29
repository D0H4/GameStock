package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StockTalkServiceTest {
    @Test
    void keepsAtMostOneBlankLineAndTrimsSpaces() {
        assertEquals("오늘 상한가\n\n가자 !", StockTalkService.cleanBody("  오늘   상한가 \r\n\n\n\n  가자\t!  "));
    }

    @Test
    void countsCharactersNotBytes() {
        assertDoesNotThrow(() -> StockTalkService.cleanBody("가".repeat(StockTalkService.MAX_LENGTH)));
        assertThrows(IllegalArgumentException.class, () -> StockTalkService.cleanBody("가".repeat(StockTalkService.MAX_LENGTH + 1)));
    }

    @Test
    void rejectsEmptyLinksAndBlockedWords() {
        assertThrows(IllegalArgumentException.class, () -> StockTalkService.cleanBody(" \n "));
        assertThrows(IllegalArgumentException.class, () -> StockTalkService.cleanBody("여기 보세요 https://a.b"));
        assertThrows(IllegalArgumentException.class, () -> StockTalkService.cleanBody("씨 . 발"));
        assertThrows(IllegalArgumentException.class, () -> StockTalkService.cleanBody("소\u0007리"));
    }
}
