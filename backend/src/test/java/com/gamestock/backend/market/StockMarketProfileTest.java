package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockMarketProfileTest {
    @Test void newlyListedBotsCanTradeAcrossTenPercentWithoutAnInvisibleStaticWall() {
        for(String code:java.util.List.of("LT","MH")) {
            long reference=code.equals("LT")?7270:6620;
            long last=code.equals("LT")?7990:7280;
            long next=last+10;
            var accounts=new java.util.HashMap<Long,BatchOrderBook.Account>();
            accounts.put(1L,new BatchOrderBook.Account(1,100000,false,true));
            accounts.put(2L,new BatchOrderBook.Account(2,0,false,true));
            var stocks=new java.util.HashMap<Long,BatchOrderBook.Stock>();
            stocks.put(1L,new BatchOrderBook.Stock(1,code,last,reference,reference,last,true));
            var holdings=new java.util.HashMap<BatchOrderBook.PositionKey,BatchOrderBook.Holding>();
            holdings.put(new BatchOrderBook.PositionKey(2,1),new BatchOrderBook.Holding(1,1,last,0));
            var book=new BatchOrderBook(accounts,stocks,holdings,java.util.List.of(),0,1000);
            book.submit(2,1,"SELL","LIMIT",1,next,1000,10000);
            book.submit(1,1,"BUY","LIMIT",1,next,1000,10000);
            assertEquals(1,book.fills.size(),code+" must execute an otherwise valid funded order");
            assertEquals(next,stocks.get(1L).last);
            assertEquals(100000,accounts.get(1L).cash+accounts.get(2L).cash);
            assertTrue(PriceLimitPolicy.botBand(code,reference).contains(next));
        }
    }

    @Test void newListingsUseTheSameSmallCapClassificationAsTheirDefaultProfile() {
        for(String code:java.util.List.of("LT","MH","NEW_LISTING")) {
            assertTrue(StockMarketProfile.isSpeculative(code));
            assertEquals(StockMarketProfile.SMALL_CAP_ACTIVE_USERS,StockMarketProfile.of(code).activeUsers());
            assertEquals(Double.POSITIVE_INFINITY,PriceLimitPolicy.staticViRate(code));
            assertEquals(.20,PriceLimitPolicy.dynamicViRate(code));
        }
    }

    @Test
    void virtualMarketCapsDifferentiateStocksAndTheirLiquidity() {
        var small = StockMarketProfile.of("TR");
        var large = StockMarketProfile.of("GI");
        var otherSmall = StockMarketProfile.of("AK");
        var otherLarge = StockMarketProfile.of("WH");

        assertEquals(small.activeUsers() * StockMarketProfile.VIRTUAL_VALUE_PER_ACTIVE_USER, small.marketCap());
        assertEquals(5_000_000_000L, small.marketCap());
        assertEquals(1_500_000_000_000L, large.marketCap());
        assertTrue(small.marketCap() < otherSmall.marketCap());
        assertTrue(small.movementWeight() > otherSmall.movementWeight());
        assertTrue(large.activeUsers() > small.activeUsers());
        assertTrue(large.marketCap() > small.marketCap());
        assertTrue(large.movementWeight() < small.movementWeight());
        assertTrue(large.liquidityWeight() > small.liquidityWeight());
        assertTrue(large.marketCap() > otherLarge.marketCap());
        assertTrue(large.liquidityWeight() >= otherLarge.liquidityWeight());
    }

    @Test
    void largerMarketCapDampensNoiseAndAddsRestoringPressure() {
        var base = new MarketEnvironment(.8, .6, 2.4, 1.8, 1.1, 1.5, .7, .5, .8, .4, 1.9, 2.1);
        var small = StockMarketProfile.applyTo("TR", base);
        var large = StockMarketProfile.applyTo("GI", base);

        assertTrue(small.directionBias() > base.directionBias());
        assertTrue(Math.abs(large.directionBias()) < Math.abs(base.directionBias()));
        assertTrue(large.volatilityMultiplier() < base.volatilityMultiplier());
        assertTrue(large.buyPressure() < base.buyPressure());
        assertTrue(large.spreadMultiplier() < base.spreadMultiplier());
        assertTrue(large.meanReversionStrength() > small.meanReversionStrength());
        assertEquals(.03, StockMarketProfile.normalDailyMove("GI"));
        assertEquals(StockMarketProfile.activityTarget("GI",300),StockMarketProfile.activityTarget("TR",300));
        assertEquals(16000, StockMarketProfile.valuationAnchor("GI",10000,16000,0));
        assertEquals(16000, StockMarketProfile.valuationAnchor("GI",10000,16000,.1),1e-9);
    }

    @Test void allSymbolsUseTheFullDecisionCadenceAndActivityTarget() {
        for(String code:java.util.List.of("UMA","BA","GOV","ZZZ","GI","SR","AK","WH","CRK","VR","ES","KRP","OW","PW","TR")) {
            assertEquals(1,StockMarketProfile.decisionIntervalWeight(code),code);
            assertEquals(300,StockMarketProfile.activityTarget(code,300),code);
        }
    }

    @Test
    void publicStockCarriesTheProfileUsedByTheMarket() {
        var profile = StockMarketProfile.of("BA");
        var stock = new MarketModels.Stock("BA", "BA", "RPG", 10_000, 0, 0);

        assertEquals(profile.activeUsers(), stock.activeUsers());
        assertEquals(profile.marketCap(), stock.marketCap());
        assertEquals(profile.movementWeight(), stock.movementWeight(), 1e-12);
    }

    @Test void apiCarriesExactDailyLimitsAndReferenceForEveryStock() {
        var small=new MarketModels.Stock("TR","TR","RPG",30000,200,1,null,10000);
        assertEquals(10000,small.referencePrice());
        assertEquals(13000,small.limitUp());
        assertEquals(7000,small.limitDown());
        var large=new MarketModels.Stock("GI","GI","RPG",10100,1,1,null,10000);
        assertEquals(13000,large.limitUp());
        assertEquals(7000,large.limitDown());
    }
}
