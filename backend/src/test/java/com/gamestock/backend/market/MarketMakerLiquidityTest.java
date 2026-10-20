package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketMakerLiquidityTest {
    private final MarketMakerEngine maker=new MarketMakerEngine();
    private final MarketMakerEngine.QuoteConstraints limits=new MarketMakerEngine.QuoteConstraints(8000,12000,0,0);

    @Test void buyingDemandGetsMoreAsksAndSellingDemandGetsMoreBids() {
        var risk=new MarketMakerEngine.RiskBook(5000000,1000,1000,2000,20000000);
        for(double pressure:List.of(-1.0,1.0)) {
            var quotes=maker.quotes(metrics(0),MarketEnvironment.neutral(),risk,10,1,limits,10000,pressure);
            int buys=quotes.stream().filter(q->q.side().equals("BUY")).mapToInt(MarketMakerEngine.Quote::quantity).sum();
            int sells=quotes.stream().filter(q->q.side().equals("SELL")).mapToInt(MarketMakerEngine.Quote::quantity).sum();
            assertTrue(pressure>0?sells>buys:buys>sells);
            assertTrue(quotes.stream().allMatch(q->q.quantity()>0 && q.quantity()<=32));
            assertEquals(8,quotes.size());
        }
    }

    @Test void scarceCashAndInventoryStillFundAllFourLevels() {
        var risk=new MarketMakerEngine.RiskBook(125000,12,100,400,4000000);
        var quotes=maker.quotes(metrics(0),MarketEnvironment.neutral(),risk,10,1,limits);
        assertEquals(4,quotes.stream().filter(q->q.side().equals("BUY")).count());
        assertEquals(4,quotes.stream().filter(q->q.side().equals("SELL")).count());
        assertEquals(12,quotes.stream().filter(q->q.side().equals("SELL")).mapToInt(MarketMakerEngine.Quote::quantity).sum());
        assertTrue(quotes.stream().filter(q->q.side().equals("BUY"))
                .mapToLong(q->{long gross=(long)q.price()*q.quantity();return gross+BatchOrderBook.fee(gross);}).sum()<=risk.cashBudget());
        assertTrue(quotes.stream().allMatch(q->q.quantity()>0 && q.price()>=8000 && q.price()<=12000));
    }

    @Test void scarceFundedLadderSupportsHumanRoundTripWithoutCreatingAssets() {
        var accounts=new HashMap<Long,BatchOrderBook.Account>();
        accounts.put(1L,new BatchOrderBook.Account(1,125000,true,true));
        accounts.put(2L,new BatchOrderBook.Account(2,100000,false,false));
        var stocks=new HashMap<Long,BatchOrderBook.Stock>();
        stocks.put(1L,new BatchOrderBook.Stock(1,"ES",10000,10000,10000,10000,true));
        var holdings=new HashMap<BatchOrderBook.PositionKey,BatchOrderBook.Holding>();
        holdings.put(new BatchOrderBook.PositionKey(1,1),new BatchOrderBook.Holding(12,12,10000,0));
        var book=new BatchOrderBook(accounts,stocks,holdings,List.of(),0,1000);
        book.lpCashBudgets.put(1L,125000L);
        var risk=new MarketMakerEngine.RiskBook(125000,12,100,400,4000000);
        for(var quote:maker.quotes(metrics(0),MarketEnvironment.neutral(),risk,10,1,limits))
            assertNotNull(book.submit(1,1,quote.side(),"LIMIT",quote.quantity(),(long)quote.price(),1000,10000));
        assertTrue(book.fills.isEmpty());
        assertEquals(4,book.snapshot(1).bids().size());assertEquals(4,book.snapshot(1).asks().size());
        assertEquals("FILLED",book.submit(2,1,"BUY","MARKET",5,0,1001,0).status);
        assertEquals("FILLED",book.submit(2,1,"SELL","MARKET",5,0,1002,0).status);
        long reserved=book.working(1,1).stream().mapToLong(o->o.reservedCash).sum();
        long fees=book.fills.stream().mapToLong(f->f.buyerFee()+f.sellerFee()).sum();
        assertEquals(225000,accounts.values().stream().mapToLong(a->a.cash).sum()+reserved+fees);
        assertEquals(12,holdings.values().stream().mapToLong(BatchOrderBook.Holding::quantity).sum());
        assertEquals(0,book.holding(2,1).quantity());
    }

    private PriceMetricService.Metrics metrics(double shortReturn) {
        return new PriceMetricService.Metrics(10000,10000,10000,10000,10000,9990,10010,0,0,0,
                shortReturn,0,0,10000,10000,0,0,50,0,0,10,0,0,10000,10);
    }
}
