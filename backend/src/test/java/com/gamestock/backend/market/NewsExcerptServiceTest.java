package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NewsExcerptServiceTest {
    private static final String FIRST = "첫 문단은 게임 업데이트 소식을 전하는 본문의 시작 부분으로 충분히 긴 문장입니다.";
    private static final String SECOND = "둘째 문단은 이어지는 설명으로 이벤트 기간과 보상 내용을 자세히 알려 주는 문장입니다.";

    @Test void lineBreakBodyDoesNotPickParagraphsAfterTheContainer() {
        // 본문은 <br>로만 나뉘고, 컨테이너 밖 발행인 정보가 <p>로 되어 있는 언론사.
        String html = "<div class=\"article\" itemprop=\"articleBody\"><div class=\"photo\"><p>[사진=제공]</p></div>"
                + FIRST + "<br /><br />" + SECOND + "</div>"
                + "<div class=\"info\"><p>제호:어떤신문 | 정기간행물 등록번호:서울 아00000 | 창간연월일:2005년</p>"
                + "<p>대표이사:누구 | 발행인/편집인:누구 | 청소년보호책임자:누구입니다</p></div>";
        assertEquals(List.of(FIRST, SECOND), NewsExcerptService.extractParagraphs(html));
    }

    @Test void divParagraphBodyIgnoresRelatedListsLaterInThePage() {
        String html = "<div id=\"articleBody\" itemprop=\"articleBody\"><div class='article_paragarph'>" + FIRST + "</div> "
                + "<div class='article_paragarph'>" + SECOND + "</div></div>"
                + "<ul><li><p>다른 연예 기사 제목이 여기에 길게 들어가 있는 숏폼 영상 목록 항목입니다</p></li></ul>";
        assertEquals(List.of(FIRST, SECOND), NewsExcerptService.extractParagraphs(html));
    }

    @Test void videoListInsideTheContainerAfterTheBodyIsSkipped() {
        String html = "<div class=\"vcon_con detailCont\" itemprop=\"articleBody\">" + FIRST + "<br /><br />" + SECOND
                + "<div class=\"shorts\"><h2>데일리 숏</h2><ul><li>"
                + "<p class=\"w2\">'다른 게임' 모험가라면 여기! 현실로 나온 그 게임의 세계를 소개하는 영상</p></li></ul></div></div>";
        assertEquals(List.of(FIRST, SECOND), NewsExcerptService.extractParagraphs(html));
    }

    @Test void explicitArticleBodyWinsOverAPageWideViewWrapper() {
        // 페이지 전체를 감싸는 id="article-view" 안의 고정 헤더에 제목이 있다.
        String html = "<div id=\"article-view\"><div id=\"sticky-header\"><div class=\"item titles\">"
                + "<span>[주간동향] 이번 주 PC방 순위에서 가장 크게 오른 게임은 무엇일까요</span></div></div>"
                + "<article id=\"article-view-content-div\" itemprop=\"articleBody\"><p>" + FIRST + "</p><p>" + SECOND + "</p></article></div>";
        assertEquals(List.of(FIRST, SECOND), NewsExcerptService.extractParagraphs(html));
    }

    @Test void nestedSameTagElementsDoNotEndTheContainerEarly() {
        String html = "<div itemprop=\"articleBody\"><div><div>" + FIRST + "</div></div><p>" + SECOND + "</p></div><p>밖에 있는 문단은 본문이 아니므로 잡히면 안 되는 긴 문장입니다</p>";
        int end = NewsExcerptService.elementEnd(html, 0, html.indexOf('>') + 1);
        assertTrue(html.substring(0, end).contains(SECOND));
        assertFalse(html.substring(0, end).contains("밖에 있는"));
        assertEquals(List.of(FIRST, SECOND), NewsExcerptService.extractParagraphs(html));
    }

    @Test void excerptsStoredBeforeTheCurrentRulesAreExtractedAgain() {
        assertNull(NewsExcerptService.readStored("{\"paragraphs\":[\"예전 규칙으로 뽑은 문단\"],\"url\":\"https://example.com\"}"));
        var current = NewsExcerptService.readStored("{\"v\":" + NewsExcerptService.EXCERPT_VERSION
                + ",\"paragraphs\":[\"" + FIRST + "\"],\"url\":\"https://example.com\"}");
        assertNotNull(current);
        assertEquals(List.of(FIRST), current.paragraphs());
    }
}
