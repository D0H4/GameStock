package com.gamestock.backend.market;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="market.test.jdbc",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class NewsValuationIntegrationTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private NewsValuation model;
    private String schema;
    private final Instant now=Instant.parse("2030-01-01T10:00:00Z");

    @BeforeEach void setup() {
        source=new SingleConnectionDataSource(System.getProperty("market.test.jdbc"),"root","",true);
        jdbc=new JdbcTemplate(source);schema="news_value_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+schema);jdbc.execute("USE "+schema);
        jdbc.execute("CREATE TABLE stocks(id BIGINT PRIMARY KEY,stock_code VARCHAR(10))");
        jdbc.execute("CREATE TABLE market_price_metrics(stock_id BIGINT PRIMARY KEY,hidden_fundamental DOUBLE)");
        jdbc.execute("CREATE TABLE market_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,stock_id BIGINT,event_type VARCHAR(10),title TEXT,description TEXT,impact DOUBLE,published_at TIMESTAMP(3),created_at TIMESTAMP(3))");
        jdbc.update("INSERT INTO stocks VALUES(1,'GI')");jdbc.update("INSERT INTO market_price_metrics VALUES(1,10000)");
        NewsValuation.ensureTables(jdbc);model=new NewsValuation(jdbc);model.project(1,"GI",now);
    }
    @AfterEach void cleanup() {if(jdbc!=null)jdbc.execute("DROP DATABASE "+schema);if(source!=null)source.destroy();}
    private void news(String title,double score,Instant at) {
        jdbc.update("INSERT INTO market_events(stock_id,event_type,title,description,impact,published_at,created_at) VALUES(1,'NEWS',?,'원신 신규 콘텐츠',?,?,?)",title,score,Timestamp.from(at),Timestamp.from(at));
    }
    private double value() {return jdbc.queryForObject("SELECT hidden_fundamental FROM market_price_metrics WHERE stock_id=1",Double.class);}

    @Test void pennyStockBadNewsCanLowerValueBelowOneHundredAndSurvivesRestart() {
        jdbc.update("UPDATE stocks SET stock_code='KD' WHERE id=1");
        jdbc.update("DELETE FROM market_fundamental_state");
        jdbc.update("UPDATE market_price_metrics SET hidden_fundamental=100");
        model.project(1,"KD",now);
        news("카트라이더: 드리프트 서버 장애",-10,now);
        model.project(1,"KD",now);
        double lower=value();
        assertTrue(lower>=1 && lower<100);
        new NewsValuation(jdbc).project(1,"KD",now);
        assertEquals(lower,value(),1e-8);
        model.project(1,"KD",now.plusSeconds(10*86400));
        assertTrue(value()>lower && value()<100,"The permanent loss must not snap back to 100");
    }

    @Test void restartAndRepeatedProjectionNeverCompoundTheSameNewsAndTheEconomicEffectSurvivesExpiry() {
        news("원신 신규 지역 공개",8,now);model.project(1,"GI",now);double first=value();assertTrue(first>10000);
        for(int i=0;i<20;i++)new NewsValuation(jdbc).project(1,"GI",now);
        assertEquals(first,value(),1e-8);
        model.project(1,"GI",now.plusSeconds(86400));double permanent=value();
        assertTrue(permanent>10000 && permanent<first);
        model.project(1,"GI",now.plusSeconds(10*86400));assertEquals(permanent,value(),1e-8);
        model.project(1,"GI",now.plusSeconds(11*86400));assertEquals(permanent,value(),1e-8);
    }

    @Test void duplicatesBatchOrderCorrectionsAndDeletionHaveOneContribution() {
        news("원신 신규 지역과 캐릭터 공개 - 게임뉴스",8,now);model.project(1,"GI",now);double first=value();
        news("원신 신규 지역과 캐릭터 공개 | 뉴스",8,now);model.project(1,"GI",now);assertEquals(first,value(),1e-8);
        jdbc.update("UPDATE market_events SET impact=-8");model.project(1,"GI",now);assertTrue(value()<10000);
        jdbc.update("DELETE FROM market_events");model.project(1,"GI",now);assertEquals(10000,value(),1e-8);
        for(int i=0;i<10;i++)news("원신 신규 지역과 캐릭터 공개",8,now);
        model.project(1,"GI",now);assertEquals(first,value(),1e-8);
    }

    @Test void upgradePreservesExistingEconomicValueAndDoesNotReplayOldNews() {
        jdbc.update("DELETE FROM market_fundamental_state");jdbc.update("UPDATE market_price_metrics SET hidden_fundamental=12000");
        news("원신 신규 지역 공개",8,now);model.project(1,"GI",now);assertEquals(12000,value(),1e-8);
        new NewsValuation(jdbc).project(1,"GI",now);assertEquals(12000,value(),1e-8);
    }

    @Test void republishedArchivedArticleCannotReplayAfterRestart() {
        republishedArticle(false);
    }

    @Test void republishedArticleCannotReplayWhenArchivingWasDelayed() {
        republishedArticle(true);
    }

    private void republishedArticle(boolean delayedArchive) {
        news("원신 신규 지역 공개",8,now);model.project(1,"GI",now);
        long id=jdbc.queryForObject("SELECT id FROM market_events",Long.class);
        model.project(1,"GI",now.plusSeconds(86400));double permanent=value();
        Instant later=now.plusSeconds(10*86400);
        if(!delayedArchive)model.project(1,"GI",later);
        jdbc.update("UPDATE market_events SET published_at=? WHERE id=?",Timestamp.from(later),id);
        model=new NewsValuation(jdbc);
        assertTrue(model.articles("GI",later,86400).isEmpty(),"Republishing cannot reintroduce the transient news signal either");
        model.project(1,"GI",later);
        assertEquals(permanent,value(),1e-8);
        assertTrue(jdbc.queryForObject("SELECT archived FROM market_news_valuation WHERE news_id=?",Boolean.class,id));
        assertEquals("",jdbc.queryForObject("SELECT description FROM market_news_valuation WHERE news_id=?",String.class,id));
        jdbc.update("UPDATE market_events SET published_at=?,impact=-8 WHERE id=?",Timestamp.from(later.plusSeconds(86400)),id);
        new NewsValuation(jdbc).project(1,"GI",later.plusSeconds(86400));
        assertEquals(permanent,value(),1e-8,"Corrections outside the retained window require an explicit value adjustment");
    }

    @Test void legacyUpgradeBackfillsDiscardedReceiptsWithoutChangingValueAndIsRepeatable() {
        Instant realNow=Instant.now();
        news("원신 신규 지역 공개",8,realNow.minusSeconds(10*86400));
        long id=jdbc.queryForObject("SELECT id FROM market_events",Long.class);
        jdbc.execute("ALTER TABLE market_news_valuation DROP INDEX ix_news_valuation_active,DROP COLUMN archived");
        // The feed may have changed its date before the upgrade. Original ingestion time remains.
        jdbc.update("UPDATE market_events SET published_at=? WHERE id=?",Timestamp.from(realNow),id);
        NewsValuation.ensureTables(jdbc);NewsValuation.ensureTables(jdbc);
        assertTrue(jdbc.queryForObject("SELECT archived FROM market_news_valuation WHERE news_id=?",Boolean.class,id));
        jdbc.update("UPDATE market_events SET published_at=? WHERE id=?",Timestamp.from(realNow),id);
        new NewsValuation(jdbc).project(1,"GI",realNow);
        assertEquals(10000,value(),1e-8);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM market_news_valuation",Integer.class));
    }
}
