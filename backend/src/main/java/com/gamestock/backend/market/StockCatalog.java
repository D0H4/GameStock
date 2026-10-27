package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** The listings shared with the web universe; all prices are simulated. */
public final class StockCatalog {
    private StockCatalog() {}

    private record Listing(String code, String name, String developer, String genre, long initialPrice) {}

    private static final List<Listing> LISTINGS = List.of(
            new Listing("UMA", "우마무스메 프리티더비", "Cygames", "RPG", 12_600),
            new Listing("BA", "블루 아카이브", "Nexon", "RPG", 48_000),
            new Listing("GOV", "승리의 여신: 니케", "ShiftUp", "RPG", 92_000),
            new Listing("ZZZ", "젠레스 존 제로", "HoYoverse", "액션", 78_000),
            new Listing("GI", "원신", "HoYoverse", "오픈월드", 215_000),
            new Listing("SR", "붕괴: 스타레일", "HoYoverse", "RPG", 128_000),
            new Listing("AK", "명일방주: 엔드필드", "Hypergryph", "전략 RPG", 12_000),
            new Listing("WH", "명조: 워더링 웨이브", "Kuro Games", "액션", 64_000),
            new Listing("CRK", "쿠키런: 킹덤", "Devsisters", "RPG", 3_200),
            new Listing("VR", "발로란트", "Riot Games", "FPS", 28_000),
            new Listing("ES", "에픽세븐", "Smilegate", "RPG", 1_600),
            new Listing("KRP", "카트라이더 러쉬플러스", "Nexon", "레이싱", 4_800),
            new Listing("OW", "오버워치", "Blizzard Entertainment", "FPS", 105_000),
            new Listing("PW", "팰월드", "Pocketpair", "생존", 8_200),
            new Listing("TR", "트릭컬 리바이브", "EPID Games", "RPG", 900),
            new Listing("GKM", "학원 아이돌마스터", "QualiArts", "육성", 18_500),
            new Listing("KR", "카트라이더", "Nexon", "레이싱", 650),
            new Listing("MC", "마인크래프트", "Mojang Studios", "생존", 158_000),
            new Listing("KD", "카트라이더: 드리프트", "Nitro Studio", "레이싱", 100),
            new Listing("MAPLE", "메이플스토리", "Nexon", "RPG", 56_000));

    static java.util.Map<String,Long> initialPrices() {
        return LISTINGS.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Listing::code,Listing::initialPrice));
    }

    /** Add missing games/stocks without changing existing prices or trading data. */
    public static void ensureListings(JdbcTemplate jdbc) {
        renameCode(jdbc, "PX", "CRK");
        renameCode(jdbc, "LT", "VR");
        renameCode(jdbc, "MH", "KRP");
        renameCode(jdbc, "EL", "OW");
        renameCode(jdbc, "SD", "TR");
        replaceListing(jdbc, "AK", "명일방주", "명일방주: 엔드필드", "Hypergryph", "전략 RPG");
        replaceListing(jdbc, "OW", "엘든 링", "오버워치", "Blizzard Entertainment", "FPS");
        replaceListing(jdbc, "CRK", "페르소나5: 더 팬텀 X", "쿠키런: 킹덤", "Devsisters", "RPG");
        replaceListing(jdbc, "TR", "스타듀 밸리", "트릭컬 리바이브", "EPID Games", "RPG");
        replaceListing(jdbc, "VR", "림버스 컴퍼니", "발로란트", "Riot Games", "FPS");
        replaceListing(jdbc, "KRP", "몬스터헌터 와일즈", "카트라이더 러쉬플러스", "Nexon", "레이싱");
        for (Listing listing : LISTINGS) {
            jdbc.update("""
                    INSERT INTO games (name, developer, genre)
                    SELECT ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM games WHERE name = ?)
                    """, listing.name(), listing.developer(), listing.genre(), listing.name());
            jdbc.update("""
                    INSERT INTO stocks (game_id, stock_code, current_price, previous_price, total_volume)
                    SELECT g.id, ?, ?, ?, 0 FROM games g
                    WHERE g.name = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM stocks s JOIN games existing ON existing.id = s.game_id
                          WHERE s.stock_code = ? OR existing.name = ?
                      )
                    ORDER BY g.id LIMIT 1
                    """, listing.code(), listing.initialPrice(), listing.initialPrice(), listing.name(), listing.code(), listing.name());
        }
    }

    /** Rename a code in place; everything else references the stock ID, so holdings and history carry over. */
    private static void renameCode(JdbcTemplate jdbc, String oldCode, String newCode) {
        jdbc.update("""
                UPDATE stocks SET stock_code = ?
                WHERE stock_code = ?
                  AND NOT EXISTS (SELECT 1 FROM (SELECT stock_code FROM stocks WHERE stock_code = ?) taken)
                """, newCode, oldCode, newCode);
    }

    /** Retain stock IDs, codes, holdings and history when replacing a game. */
    private static void replaceListing(JdbcTemplate jdbc, String code, String oldName,
                                       String newName, String developer, String genre) {
        jdbc.update("""
                UPDATE games g JOIN stocks s ON s.game_id = g.id
                SET g.name = ?, g.developer = ?, g.genre = ?
                WHERE s.stock_code = ? AND g.name IN (?, ?)
                """, newName, developer, genre, code, oldName, newName);
    }
}
