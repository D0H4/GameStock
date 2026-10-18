package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoanServiceTest {
    @Test
    void interestCompoundsPerMissedDayAndRoundsUp() {
        assertEquals(0, LoanService.interestFor(1_000_000, 0));
        assertEquals(1_000, LoanService.interestFor(1_000_000, 1));
        assertEquals(2_001, LoanService.interestFor(1_000_000, 2));
        assertEquals(1, LoanService.interestFor(1, 1));
    }

    @Test
    void repayTargetRestoresWarningRatio() {
        // 총 자산 110만, 대출 100만: 갚은 뒤 (110만-x)/(100만-x)=1.4 가 되는 x=75만
        assertEquals(750_000, LoanService.repayTarget(1_100_000, 1_000_000));
        // 이미 140% 이상이면 갚을 필요가 없다.
        assertEquals(0, LoanService.repayTarget(1_500_000, 1_000_000));
        // 총 자산이 대출보다 작으면 전액을 갚아야 한다.
        assertEquals(1_000_000, LoanService.repayTarget(900_000, 1_000_000));
    }
}
