package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DerivativePriceOracleTest {
    private List<DerivativePriceOracle.Bucket> tape(double price) {
        var rows=new ArrayList<DerivativePriceOracle.Bucket>();
        for(long second=40;second<100;second++)rows.add(new DerivativePriceOracle.Bucket(second,10,10*price));
        return rows;
    }
    @Test void isolatedLastPrintCannotMoveTheReferenceEvenWithLargeVolume() {
        var rows=tape(10000); rows.set(59,new DerivativePriceOracle.Bucket(99,1000000,1e12));
        assertEquals(10000,DerivativePriceOracle.reference(rows,100).orElseThrow());
        assertEquals(11000,DerivativePriceOracle.reference(tape(11000),100).orElseThrow());
    }
    @Test void thinShortOrStaleWindowsCannotAuthorizeTrading() {
        assertTrue(DerivativePriceOracle.reference(tape(10000).subList(50,60),100).isEmpty());
        var tiny=tape(10000).stream().map(b->new DerivativePriceOracle.Bucket(b.second(),1,10000)).toList();
        assertTrue(DerivativePriceOracle.reference(tiny,100).isEmpty());
        assertTrue(DerivativePriceOracle.reference(tape(10000),120).isEmpty());
    }
}
