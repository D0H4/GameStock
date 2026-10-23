package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NewsValuationTest {
    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private NewsValuation.Article article(long id, String title, double impact) {
        return new NewsValuation.Article(id,title,"",impact,NOW);
    }

    @Test void impactIsContinuousAcrossTheFormerFiveToSixCliff() {
        double low=NewsValuation.effect(article(1,"원신 업데이트",5.999));
        double high=NewsValuation.effect(article(2,"원신 업데이트",6.001));
        assertTrue(high>low); assertTrue(high-low<.00002);
        assertEquals(.0125,NewsValuation.effect(article(1,"원신 업데이트",5)),1e-12);
        assertEquals(.018,NewsValuation.effect(article(1,"원신 업데이트",6)),1e-12);
    }

    @Test void syndicatedHeadlinesCountOnceAndDistinctVersionsRemainDistinct() {
        var a=article(1,"원신 5.0 업데이트 신규 지역 공개 - 게임뉴스",8);
        var b=article(2,"원신 5.0 업데이트, 신규 지역 공개 | 뉴스",8);
        var c=article(3,"원신 6.0 업데이트 신규 지역 공개",8);
        assertTrue(NewsValuation.duplicate(a,b));assertFalse(NewsValuation.duplicate(a,c));
        assertEquals(NewsValuation.bias(List.of(a),NOW),NewsValuation.bias(List.of(b,a),NOW));
        assertEquals(2,NewsValuation.events(List.of(a,b,c)).size());
        assertFalse(NewsValuation.duplicate(a,article(4,"원신 5.0 업데이트 신규 지역 공개 취소",-8)));
    }

    @Test void eleventhNeutralHeadlineCannotEvictAnEconomicEvent() {
        var news=new ArrayList<NewsValuation.Article>(); news.add(article(1,"원신 신작 흥행",8));
        double initial=NewsValuation.bias(news,NOW);
        for(int i=2;i<50;i++)news.add(article(i,"원신 일정 안내 "+i,0));
        assertEquals(initial,NewsValuation.bias(news,NOW));
        Collections.reverse(news);assertEquals(initial,NewsValuation.bias(news,NOW));
        assertEquals(0,NewsValuation.bias(news,NOW.plusSeconds(86400)));
    }

    @Test void indexedCandidatesPreserveExhaustiveGroupingIncludingExpiryAndCompetingMatches() {
        Random random = new Random(7781);
        List<NewsValuation.Article> articles = new ArrayList<>();
        String[] titles = {"원신 신규 지역과 캐릭터 공개", "원신 신규 지역과 캐릭터 공개 예정", "원신 신규 지역과 캐릭터 공개 취소",
                "원신 신규 지역과 캐릭터 공개 - 게임뉴스", "원신 신규 지역과 캐릭터 공개 | 뉴스", "원신 업데이트 버전 5.0 공개", "원신 업데이트 버전 6.0 공개"};
        for (int i=0; i<250; i++) articles.add(new NewsValuation.Article(i,
                titles[random.nextInt(titles.length)] + (i % 5 == 0 ? " 추가 콘텐츠 상세 안내" : ""),
                i % 4 == 0 ? "출처: https://example.test/" + (i % 11) : "", random.nextInt(21)-10,
                NOW.plusSeconds(random.nextInt(5*86400))));
        // Exactly 48 hours is still in the group; one second later is a different event.
        articles.add(new NewsValuation.Article(1001,"경계만 확인하는 고유 제목","",8,NOW));
        articles.add(new NewsValuation.Article(1002,"경계만 확인하는 고유 제목","",7,NOW.plusSeconds(2*86400)));
        articles.add(new NewsValuation.Article(1003,"경계만 확인하는 고유 제목","",6,NOW.plusSeconds(2*86400+1)));
        Collections.shuffle(articles,random);
        List<List<NewsValuation.Article>> groups = new ArrayList<>();
        for (var article : articles.stream().sorted(Comparator.comparing(NewsValuation.Article::published).thenComparingLong(NewsValuation.Article::id)).toList()) {
            var group = groups.stream().filter(g -> article.published().getEpochSecond()-g.get(0).published().getEpochSecond()<=2*86400
                    && NewsValuation.duplicate(g.get(0),article)).findFirst().orElse(null);
            if (group==null) {group=new ArrayList<>();groups.add(group);}
            group.add(article);
        }
        var expected = groups.stream().map(g -> new NewsValuation.Event(g,
                NewsValuation.effect(g.stream().max(Comparator.comparingDouble((NewsValuation.Article a)->Math.abs(NewsValuation.effect(a))).thenComparingLong(NewsValuation.Article::id)).orElseThrow()),
                g.get(0).published())).toList();
        assertEquals(expected,NewsValuation.events(articles));
    }

    @Test @org.junit.jupiter.api.Timeout(5)
    void largeDistinctFeedDoesNotRequirePairwiseHeadlineComparisons() {
        List<NewsValuation.Article> articles = new ArrayList<>();
        for (int i=0; i<10000; i++) articles.add(new NewsValuation.Article(i,"원신 신규 업데이트 "+i+" 콘텐츠 공개",
                "출처: https://example.test/article/"+i,5,NOW.minusSeconds(i)));
        assertEquals(10000,NewsValuation.events(articles).size());
    }
}
