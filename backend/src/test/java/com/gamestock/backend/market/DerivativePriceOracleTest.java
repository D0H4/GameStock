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

    private List<DerivativePriceOracle.Bucket> sparseTape() {
        var rows=new ArrayList<DerivativePriceOracle.Bucket>();
        for(long second=600;second<1000;second+=20)
            rows.add(new DerivativePriceOracle.Bucket(second,5,50000));
        return rows;
    }

    @Test void sparseExecutionsRetainTheEvidenceFloorAcrossALongerWindow() {
        var rows=sparseTape();
        assertTrue(DerivativePriceOracle.reference(rows,1000).isEmpty());
        assertEquals(10000,DerivativePriceOracle.sparseReference(rows,1000).orElseThrow());
    }

    @Test void sparseReferenceUsesTheShortestSufficientTailAndResistsAnIsolatedPrint() {
        var rows=sparseTape();
        for(long second=400;second<450;second++)
            rows.add(new DerivativePriceOracle.Bucket(second,10,500000));
        rows.set(19,new DerivativePriceOracle.Bucket(980,1000000,1e12));
        Collections.reverse(rows);
        assertEquals(10000,DerivativePriceOracle.sparseReference(rows,1000).orElseThrow());
    }

    @Test void sparseFallbackStillRejectsInsufficientSamplesSpanAndFreshness() {
        var rows=sparseTape();
        assertTrue(DerivativePriceOracle.sparseReference(rows.subList(1,20),1000).isEmpty());
        var shortSpan=new ArrayList<DerivativePriceOracle.Bucket>();
        for(long second=980;second<1000;second++)shortSpan.add(new DerivativePriceOracle.Bucket(second,10,100000));
        assertTrue(DerivativePriceOracle.sparseReference(shortSpan,1000).isEmpty());
        assertTrue(DerivativePriceOracle.sparseReference(rows,1070).isEmpty());
        // A new outlier cannot make a ten-minute-old history sufficient again.
        var expired=rows.stream().map(b->new DerivativePriceOracle.Bucket(b.second()-600,b.quantity(),b.notional())).toList();
        var isolated=new ArrayList<>(expired);
        isolated.add(new DerivativePriceOracle.Bucket(999,1000000,1e12));
        assertTrue(DerivativePriceOracle.sparseReference(isolated,1000).isEmpty());
    }

    @Test void singleShareLotsNeedAtLeastThreeMinutesOfIndependentExecutions() {
        var persistent=sparseTape().stream().map(b->new DerivativePriceOracle.Bucket(b.second(),1,10000)).toList();
        assertEquals(10000,DerivativePriceOracle.sparseReference(persistent,1000).orElseThrow());
        var brief=new ArrayList<DerivativePriceOracle.Bucket>();
        for(long second=900;second<1000;second+=5)
            brief.add(new DerivativePriceOracle.Bucket(second,1,10000));
        assertTrue(DerivativePriceOracle.sparseReference(brief,1000).isEmpty());
        // More volume at a single price cannot dominate a persistent micro-lot tape.
        var outlier=new ArrayList<>(persistent);
        outlier.set(19,new DerivativePriceOracle.Bucket(980,1000000,1e12));
        assertEquals(10000,DerivativePriceOracle.sparseReference(outlier,1000).orElseThrow());
    }

    @Test void sparseFallbackDoesNotUseFutureOrInvalidBucketsToMeetItsEvidenceFloor() {
        var rows=new ArrayList<>(sparseTape().subList(1,20));
        rows.add(new DerivativePriceOracle.Bucket(1000,1000,1e7));
        rows.add(new DerivativePriceOracle.Bucket(999,0,10000));
        rows.add(new DerivativePriceOracle.Bucket(998,1000,Double.NaN));
        assertTrue(DerivativePriceOracle.sparseReference(rows,1000).isEmpty());
    }
}
