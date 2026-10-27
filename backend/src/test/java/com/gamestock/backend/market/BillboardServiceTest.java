package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BillboardServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");

    private static BillboardService.Slot slot(int fromHour, int toHour) {
        return new BillboardService.Slot(NOW.plusSeconds(fromHour * 3600L), NOW.plusSeconds(toHour * 3600L));
    }

    @Test
    void emptyBoardStartsNow() {
        assertEquals(slot(0, 3), BillboardService.nextSlot(List.of(), NOW, 3));
    }

    @Test
    void queuesAfterTheShowingMessage() {
        var taken = List.of(new BillboardService.Slot(NOW.minusSeconds(1800), NOW.plusSeconds(1800)));
        assertEquals(new BillboardService.Slot(NOW.plusSeconds(1800), NOW.plusSeconds(1800 + 3600)),
                BillboardService.nextSlot(taken, NOW, 1));
    }

    @Test
    void fillsAGapLeftByACancelledBooking() {
        var taken = List.of(slot(0, 1), slot(2, 3), slot(6, 7));
        assertEquals(slot(1, 2), BillboardService.nextSlot(taken, NOW, 1));
        assertEquals(slot(3, 6), BillboardService.nextSlot(taken, NOW, 3));
        assertEquals(slot(7, 13), BillboardService.nextSlot(taken, NOW, 6));
    }

    @Test
    void cleansWhitespaceAndKeepsTheText() {
        assertEquals("우마무스메 화이팅 !", BillboardService.cleanBody("  우마무스메\n  화이팅\t!  "));
    }

    @Test
    void countsCharactersNotBytes() {
        assertDoesNotThrow(() -> BillboardService.cleanBody("가".repeat(BillboardService.MAX_LENGTH)));
        assertThrows(IllegalArgumentException.class, () -> BillboardService.cleanBody("가".repeat(BillboardService.MAX_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> BillboardService.cleanBody(" 가 "));
    }

    @Test
    void rejectsLinksAndBlockedWords() {
        assertThrows(IllegalArgumentException.class, () -> BillboardService.cleanBody("여기로 오세요 https://a.b"));
        assertThrows(IllegalArgumentException.class, () -> BillboardService.cleanBody("example.com 방문"));
        assertThrows(IllegalArgumentException.class, () -> BillboardService.cleanBody("씨 . 발 진짜"));
        assertDoesNotThrow(() -> BillboardService.cleanBody("젠레스 3.0 업데이트 기대"));
    }

    @Test
    void reportsTheBookingState() {
        assertEquals("UPCOMING", BillboardService.state("BOOKED", NOW.plusSeconds(60), NOW.plusSeconds(3600), NOW));
        assertEquals("LIVE", BillboardService.state("BOOKED", NOW, NOW.plusSeconds(3600), NOW));
        assertEquals("ENDED", BillboardService.state("BOOKED", NOW.minusSeconds(3600), NOW, NOW));
        assertEquals("HIDDEN", BillboardService.state("HIDDEN", NOW, NOW.plusSeconds(3600), NOW));
    }
}
