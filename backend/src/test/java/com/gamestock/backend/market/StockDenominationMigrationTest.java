package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class StockDenominationMigrationTest {
    @Test void splitPreservesCostAndValue() {
        var result=StockDenominationMigration.convert(10,8000,300,10000,100);
        assertEquals(new StockDenominationMigration.Conversion(1000,80,0,300),result);
    }

    @Test void lessThanOneShareIsFullyCashedOut() {
        var result=StockDenominationMigration.convert(1,8000,50,10000,20000);
        assertEquals(new StockDenominationMigration.Conversion(0,0,10000,2050),result);
    }

    @Test void remainderIsPaidEvenWhenWholeSharesRemain() {
        var result=StockDenominationMigration.convert(5,12700,-200,10000,20000);
        assertEquals(new StockDenominationMigration.Conversion(2,25400,10000,-2900),result);
    }

    @Test void arbitraryRatiosPreserveAssetsAndCombinedProfitToTheWon() {
        var random=new Random(53893);
        for(int i=0;i<10000;i++) {
            int quantity=random.nextInt(5000);
            long average=random.nextInt(300000),oldMark=random.nextInt(300000)+1;
            long target=random.nextInt(300000)+1,realized=random.nextInt(200000)-100000;
            var next=StockDenominationMigration.convert(quantity,average,realized,oldMark,target);
            assertEquals(quantity*oldMark,next.quantity()*target+next.cash());
            assertEquals(realized+quantity*(oldMark-average),next.realized()+next.quantity()*(target-next.average()));
            assertTrue(next.cash()>=0 && next.cash()<target);
            assertEquals(quantity*oldMark<target,next.quantity()==0);
        }
    }

    @Test void overflowFailsInsteadOfLosingSharesOrCash() {
        assertThrows(ArithmeticException.class,()->StockDenominationMigration.convert(Integer.MAX_VALUE,100,0,100,1));
        assertThrows(ArithmeticException.class,()->StockDenominationMigration.convert(2,1,0,Long.MAX_VALUE,1));
        assertThrows(IllegalArgumentException.class,()->StockDenominationMigration.convert(1,1,0,0,100));
    }
}
