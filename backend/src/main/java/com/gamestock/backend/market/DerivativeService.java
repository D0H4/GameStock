package com.gamestock.backend.market;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.gamestock.backend.market.DerivativeModels.*;

/**
 * 모의 ETF·선물·옵션 시장.
 *
 * <p>모든 상품은 기존 주식 계좌의 현금으로 결제한다. ETF는 NAV로 즉시 매매하고,
 * 선물은 레버리지 증거금을 잡은 뒤 평가손익을 반영하며, 옵션은 블랙-숄즈 모의
 * 프리미엄을 지불하고 만기에 내재가치로 현금결제한다. 실제 금융상품이 아니다.
 */
@Service
public class DerivativeService {
    private static final Logger log = LoggerFactory.getLogger(DerivativeService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_ORDER_QUANTITY = 100;
    private static final int MAX_OPEN_POSITIONS = 60;
    private static final int SERIES_LIMIT = 120;
    private static final int SAMPLE_LIMIT = 900;
    private static final String FUTURE_CODE = "FUTIDX";

    private record EtfDefinition(String code, String name, String theme, String description,
                                 Set<String> genres, boolean indexWeighted) { }

    private record StockRow(String code, String name, String genre, long price, long reference) {
        double changePercent() { return reference <= 0 ? 0 : (price - reference) * 100.0 / reference; }
    }

    private record OpenRow(long id, long userId, String kind, String symbol, String name, String side,
                           int quantity, double entryPrice, long entryNotional, long margin, int leverage,
                           String optionType, double strike, Timestamp expiryAt, String openedAt,
                           long realizedPnl) { }

    private record PositionRow(long id, long userId, String kind, String symbol, String name, String side,
                               int quantity, double entryPrice, long entryNotional, long margin, int leverage,
                               String optionType, double strike, Timestamp expiryAt, String status,
                               String openedAt, long realizedPnl, double markPrice, long markValue) { }

    private static final List<EtfDefinition> ETFS = List.of(
            new EtfDefinition("ETFIDX", "슈엔 게임주 지수 ETF", "지수",
                    "상장된 모든 게임 종목을 시가총액 비중으로 담는 지수 추종 ETF입니다.",
                    Set.of(), true),
            new EtfDefinition("ETFRPG", "RPG 셀렉트 ETF", "RPG",
                    "RPG·전략 RPG 장르를 같은 비중으로 담는 테마 ETF입니다.",
                    Set.of("RPG", "전략 RPG"), false),
            new EtfDefinition("ETFACT", "액션 셀렉트 ETF", "액션",
                    "액션·오픈월드 장르를 같은 비중으로 담는 테마 ETF입니다.",
                    Set.of("액션", "오픈월드"), false),
            new EtfDefinition("ETFFPS", "FPS 셀렉트 ETF", "FPS",
                    "FPS 장르의 슈팅 게임을 담는 테마 ETF입니다.",
                    Set.of("FPS"), false),
            new EtfDefinition("ETFALT", "얼터너티브 셀렉트 ETF", "얼터너티브",
                    "육성·레이싱·생존 장르를 같은 비중으로 담는 테마 ETF입니다.",
                    Set.of("육성", "레이싱", "생존"), false));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();
    private final Deque<double[]> samples = new ArrayDeque<>();
    private final Map<String, Deque<Double>> series = new ConcurrentHashMap<>();

    public DerivativeService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void ensureTables() {
        ensureTables(jdbc);
    }

    /** 스키마 마이그레이션에서도 호출한다. 여러 번 실행해도 안전하다. */
    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_positions (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  kind VARCHAR(12) NOT NULL,
                  symbol VARCHAR(40) NOT NULL,
                  name VARCHAR(80) NOT NULL,
                  side VARCHAR(8) NOT NULL,
                  quantity INT NOT NULL,
                  entry_price DOUBLE NOT NULL,
                  entry_notional BIGINT NOT NULL,
                  margin BIGINT NOT NULL DEFAULT 0,
                  leverage INT NOT NULL DEFAULT 1,
                  option_type VARCHAR(4) NULL,
                  strike DOUBLE NULL,
                  expiry_at TIMESTAMP(3) NULL,
                  status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
                  mark_price DOUBLE NOT NULL DEFAULT 0,
                  mark_value BIGINT NOT NULL DEFAULT 0,
                  realized_pnl BIGINT NOT NULL DEFAULT 0,
                  opened_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  closed_at TIMESTAMP(3) NULL,
                  close_reason VARCHAR(16) NULL,
                  INDEX ix_derivative_user (user_id, status),
                  INDEX ix_derivative_expiry (status, expiry_at),
                  CONSTRAINT fk_derivative_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS derivative_trades (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  position_id BIGINT NOT NULL,
                  action VARCHAR(12) NOT NULL,
                  kind VARCHAR(12) NOT NULL,
                  symbol VARCHAR(40) NOT NULL,
                  side VARCHAR(8) NOT NULL,
                  quantity INT NOT NULL,
                  price DOUBLE NOT NULL,
                  cash_flow BIGINT NOT NULL,
                  fee BIGINT NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  INDEX ix_derivative_trades_user (user_id, id),
                  CONSTRAINT fk_derivative_trade_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
    }

    // ------------------------------------------------------------ 공개 시세

    public DerivativeSnapshot snapshot() {
        Instant now = clock.instant();
        List<StockRow> stocks = loadStocks();
        double index = weightedIndex(stocks);
        sample(index, now);
        List<EtfQuote> etfs = new ArrayList<>(ETFS.size());
        for (EtfDefinition definition : ETFS) etfs.add(etfQuote(definition, stocks, index));
        FutureQuote future = new FutureQuote(FUTURE_CODE, "게임주 지수 선물", index, 1000,
                percent(index, 1000), DerivativePricing.CONTRACT_MULTIPLIER, DerivativePricing.LEVERAGES,
                DerivativePricing.MAINTENANCE_RATE, series("FUTIDX", index));
        double volatility = currentVolatility();
        List<OptionCycle> cycles = new ArrayList<>(3);
        List<OptionContract> contracts = new ArrayList<>();
        for (String cycleId : DerivativePricing.cycleIds()) {
            Instant expiry = DerivativePricing.expiryFor(cycleId, now);
            long secondsLeft = Math.max(0, expiry.getEpochSecond() - now.getEpochSecond());
            cycles.add(new OptionCycle(cycleId, DerivativePricing.cycleLabel(cycleId),
                    DerivativePricing.cycleTag(cycleId), expiry.toString(), expiry.getEpochSecond(), secondsLeft));
            double years = DerivativePricing.yearsLeft(expiry, now);
            for (double strike : DerivativePricing.strikes(index)) {
                for (boolean call : new boolean[]{true, false}) {
                    double points = DerivativePricing.optionPrice(index, strike, years, volatility, call);
                    long premium = DerivativePricing.premiumWon(points);
                    double breakeven = call ? strike + points : strike - points;
                    String type = call ? "CALL" : "PUT";
                    String id = cycleId + "-" + (call ? "C" : "P") + (long) strike + "-" + expiry.getEpochSecond();
                    contracts.add(new OptionContract(id, cycleId, type, strike, points, premium,
                            breakeven, expiry.toString(), expiry.getEpochSecond(), secondsLeft));
                }
            }
        }
        return new DerivativeSnapshot(index, 1000, percent(index, 1000), etfs, future, cycles,
                contracts, now.toString());
    }

    private EtfQuote etfQuote(EtfDefinition definition, List<StockRow> stocks, double index) {
        List<StockRow> members = stocks.stream()
                .filter(stock -> definition.genres().isEmpty() || definition.genres().contains(stock.genre()))
                .toList();
        double totalWeight = members.stream().mapToDouble(stock -> marketCap(stock.code())).sum();
        List<Constituent> constituents = new ArrayList<>(members.size());
        double nav;
        if (members.isEmpty()) {
            nav = 1000;
        } else if (definition.indexWeighted()) {
            double weighted = members.stream()
                    .mapToDouble(stock -> marketCap(stock.code()) * stock.price() / Math.max(1, stock.reference()))
                    .sum();
            nav = 1000 * weighted / Math.max(1, totalWeight);
        } else {
            nav = 1000 * members.stream()
                    .mapToDouble(stock -> (double) stock.price() / Math.max(1, stock.reference()))
                    .average().orElse(1);
        }
        for (StockRow stock : members) {
            double weight = definition.indexWeighted()
                    ? marketCap(stock.code()) / Math.max(1, totalWeight)
                    : 1.0 / members.size();
            constituents.add(new Constituent(stock.code(), stock.name(), weight, stock.changePercent()));
        }
        constituents.sort(Comparator.comparingDouble(Constituent::weight).reversed());
        return new EtfQuote(definition.code(), definition.name(), definition.theme(), definition.description(),
                nav, 1000, percent(nav, 1000), constituents, series("ETF:" + definition.code(), nav));
    }

    private List<StockRow> loadStocks() {
        return jdbc.query("""
                SELECT s.stock_code, g.name, g.genre, s.current_price,
                       COALESCE(p.day_reference, s.current_price) AS reference
                FROM stocks s
                JOIN games g ON g.id = s.game_id
                LEFT JOIN stock_protection_state p ON p.stock_id = s.id
                ORDER BY s.id
                """, (rs, row) -> new StockRow(rs.getString("stock_code"), rs.getString("name"),
                rs.getString("genre"), rs.getLong("current_price"), rs.getLong("reference")));
    }

    private static double weightedIndex(List<StockRow> stocks) {
        double weighted = 0;
        double weight = 0;
        for (StockRow stock : stocks) {
            double cap = marketCap(stock.code());
            weighted += cap * stock.price() * 1000.0 / Math.max(1, stock.reference());
            weight += cap;
        }
        return weight == 0 ? 1000 : weighted / weight;
    }

    private static double marketCap(String code) {
        return StockMarketProfile.of(code).marketCap();
    }

    private static double percent(double current, double reference) {
        return reference <= 0 ? 0 : Math.round((current - reference) * 10_000.0 / reference) / 100.0;
    }

    // ------------------------------------------------------------ 계좌

    @Transactional(readOnly = true)
    public Account account(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<PositionRow> rows = positionRows(userId);
        Instant now = clock.instant();
        List<StockRow> stocks = loadStocks();
        double index = weightedIndex(stocks);
        Map<String, Double> navs = etfNavs(stocks, index);
        double volatility = currentVolatility();
        List<Position> positions = new ArrayList<>(rows.size());
        long positionValue = 0;
        long unrealized = 0;
        for (PositionRow row : rows) {
            Position position = toPosition(row, index, navs, volatility, now);
            positions.add(position);
            positionValue += position.value();
            unrealized += position.unrealizedPnl();
        }
        long realized = jdbc.queryForObject(
                "SELECT COALESCE(SUM(realized_pnl), 0) FROM derivative_positions WHERE user_id = ?", Long.class, userId);
        long fees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(fee), 0) FROM derivative_trades WHERE user_id = ?", Long.class, userId);
        List<Trade> trades = jdbc.query("""
                SELECT id, position_id, action, kind, symbol, side, quantity, price, cash_flow, fee, created_at
                FROM derivative_trades WHERE user_id = ? ORDER BY id DESC LIMIT 20
                """, (rs, row) -> new Trade(rs.getLong("id"), rs.getLong("position_id"), rs.getString("action"),
                rs.getString("kind"), rs.getString("symbol"), rs.getString("side"), rs.getInt("quantity"),
                rs.getDouble("price"), rs.getLong("cash_flow"), rs.getLong("fee"),
                instant(rs.getTimestamp("created_at"))), userId);
        return new Account(cash, positionValue, cash + positionValue, unrealized, realized, fees, positions, trades);
    }

    private List<PositionRow> positionRows(long userId) {
        return jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, status, opened_at, realized_pnl, mark_price, mark_value
                FROM derivative_positions WHERE user_id = ? AND status = 'OPEN' ORDER BY id DESC
                """, (rs, row) -> new PositionRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
                rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
                rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
                rs.getDouble("strike"), rs.getTimestamp("expiry_at"), rs.getString("status"),
                instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl"),
                rs.getDouble("mark_price"), rs.getLong("mark_value")), userId);
    }

    private Position toPosition(PositionRow row, double index, Map<String, Double> navs,
                                double volatility, Instant now) {
        double mark;
        long value;
        switch (row.kind()) {
            case "ETF" -> {
                mark = navs.getOrDefault(row.symbol(), row.entryPrice());
                value = Math.round(mark * row.quantity());
            }
            case "FUTURE" -> {
                mark = index;
                long pnl = Math.round((mark - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                        * row.quantity() * ("SHORT".equals(row.side()) ? -1 : 1));
                value = row.margin() + pnl;
            }
            case "OPTION" -> {
                boolean call = "CALL".equals(row.optionType());
                Instant expiry = row.expiryAt() == null ? now : row.expiryAt().toInstant();
                boolean expired = !expiry.isAfter(now);
                mark = expired
                        ? DerivativePricing.intrinsic(index, row.strike(), call)
                        : DerivativePricing.optionPrice(index, row.strike(),
                        DerivativePricing.yearsLeft(expiry, now), volatility, call);
                // 만기 결제에는 최소 프리미엄을 적용하지 않는다. OTM은 0원이다.
                value = (expired
                        ? Math.round(mark * DerivativePricing.CONTRACT_MULTIPLIER)
                        : DerivativePricing.premiumWon(mark)) * row.quantity();
            }
            default -> {
                mark = row.entryPrice();
                value = row.entryNotional();
            }
        }
        long basis = "FUTURE".equals(row.kind()) ? row.margin() : row.entryNotional();
        long unrealized = value - basis;
        double unrealizedPercent = basis <= 0 ? 0 : Math.round(unrealized * 10_000.0 / basis) / 100.0;
        return new Position(row.id(), row.kind(), row.symbol(), row.name(), row.side(), row.quantity(),
                row.entryPrice(), mark, row.entryNotional(), row.margin(), row.leverage(),
                row.optionType(), row.strike(), row.expiryAt() == null ? null : row.expiryAt().toInstant().toString(),
                value, unrealized, unrealizedPercent, row.status(), row.openedAt());
    }

    private Map<String, Double> etfNavs(List<StockRow> stocks, double index) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (EtfDefinition definition : ETFS) {
            List<StockRow> members = stocks.stream()
                    .filter(stock -> definition.genres().isEmpty() || definition.genres().contains(stock.genre()))
                    .toList();
            if (members.isEmpty()) {
                result.put(definition.code(), 1000.0);
            } else if (definition.indexWeighted()) {
                double weighted = 0;
                double weight = 0;
                for (StockRow stock : members) {
                    double cap = marketCap(stock.code());
                    weighted += cap * stock.price() / Math.max(1, stock.reference());
                    weight += cap;
                }
                result.put(definition.code(), 1000 * weighted / Math.max(1, weight));
            } else {
                result.put(definition.code(), 1000 * members.stream()
                        .mapToDouble(stock -> (double) stock.price() / Math.max(1, stock.reference()))
                        .average().orElse(1));
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 주문

    @Transactional
    public OrderResult open(long userId, OrderRequest request) {
        if (request == null) throw new IllegalArgumentException("주문 내용을 확인해 주세요.");
        int quantity = request.quantity();
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY)
            throw new IllegalArgumentException("수량은 1~" + MAX_ORDER_QUANTITY + " 사이로 입력해 주세요.");
        String kind = normalized(request.kind(), "상품 종류");
        String side = normalized(request.side(), "방향");
        lockUser(userId);
        int openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM derivative_positions WHERE user_id = ? AND status = 'OPEN'", Integer.class, userId);
        if (openCount >= MAX_OPEN_POSITIONS)
            throw new IllegalArgumentException("동시에 보유할 수 있는 파생 포지션은 " + MAX_OPEN_POSITIONS + "개까지입니다.");

        List<StockRow> stocks = loadStocks();
        double index = weightedIndex(stocks);
        Instant now = clock.instant();
        if ("ETF".equals(kind)) {
            if ("SELL".equals(side)) return closeEtfBySymbol(userId, request.symbol(), quantity, stocks, index);
            return openEtf(userId, request.symbol(), quantity, stocks, index);
        }
        if ("FUTURE".equals(kind)) return openFuture(userId, side, quantity, request.leverage(), index);
        if ("OPTION".equals(kind)) return openOption(userId, request, quantity, index, now);
        throw new IllegalArgumentException("상품 종류는 ETF, FUTURE, OPTION 중 하나입니다.");
    }

    private OrderResult openEtf(long userId, String symbol, int quantity, List<StockRow> stocks, double index) {
        EtfDefinition definition = etfDefinition(symbol);
        double nav = etfNavs(stocks, index).getOrDefault(definition.code(), 1000.0);
        long amount = Math.round(nav * quantity);
        long fee = DerivativePricing.fee(amount, DerivativePricing.ETF_FEE_RATE);
        debit(userId, amount, fee, "ETF 매수 금액");
        long id = insertPosition(userId, "ETF", definition.code(), definition.name(), "LONG", quantity,
                nav, amount, 0, 1, null, 0, null, amount);
        recordTrade(userId, id, "OPEN", "ETF", definition.code(), "LONG", quantity, nav, -amount, fee);
        return new OrderResult(definition.name() + " " + quantity + "좌 매수 · " + won(amount + fee), account(userId));
    }

    private OrderResult openFuture(long userId, String side, int quantity, Integer requestedLeverage, double index) {
        if (!"LONG".equals(side) && !"SHORT".equals(side))
            throw new IllegalArgumentException("선물 방향은 LONG 또는 SHORT입니다.");
        int leverage = requestedLeverage == null ? 5 : requestedLeverage;
        if (!DerivativePricing.LEVERAGES.contains(leverage))
            throw new IllegalArgumentException("레버리지는 " + DerivativePricing.LEVERAGES + " 중에서 선택해 주세요.");
        long notional = DerivativePricing.contractNotional(index, quantity);
        long margin = (long) Math.ceil(notional / (double) leverage);
        long fee = DerivativePricing.fee(notional, DerivativePricing.FUTURE_FEE_RATE);
        debit(userId, margin, fee, "선물 증거금");
        long id = insertPosition(userId, "FUTURE", FUTURE_CODE, "게임주 지수 선물 " + leverage + "x",
                side, quantity, index, notional, margin, leverage, null, 0, null, margin);
        recordTrade(userId, id, "OPEN", "FUTURE", FUTURE_CODE, side, quantity, index, -(margin + fee), fee);
        return new OrderResult("지수 선물 " + ("LONG".equals(side) ? "매수" : "매도") + " " + quantity
                + "계약 · 증거금 " + won(margin), account(userId));
    }

    private OrderResult openOption(long userId, OrderRequest request, int quantity, double index, Instant now) {
        String cycleId = normalized(request.cycleId(), "만기 회차");
        if (!DerivativePricing.cycleIds().contains(cycleId))
            throw new IllegalArgumentException("지원하지 않는 옵션 만기 회차입니다.");
        String type = normalized(request.side(), "옵션 종류");
        if (!"CALL".equals(type) && !"PUT".equals(type))
            throw new IllegalArgumentException("옵션 종류는 CALL 또는 PUT입니다.");
        if (request.strike() == null) throw new IllegalArgumentException("행사가를 선택해 주세요.");
        double strike = request.strike();
        if (strike <= 0 || Math.abs(strike / index - 1) > 0.06)
            throw new IllegalArgumentException("현재 지수 ±6% 안의 행사가만 거래할 수 있습니다.");
        Instant expiry = DerivativePricing.expiryFor(cycleId, now);
        if (request.expiryEpoch() != null && request.expiryEpoch() != expiry.getEpochSecond())
            throw new IllegalArgumentException("만기가 지난 옵션입니다. 새 회차를 선택해 주세요.");
        double points = DerivativePricing.optionPrice(index, strike,
                DerivativePricing.yearsLeft(expiry, now), currentVolatility(), "CALL".equals(type));
        long perContract = DerivativePricing.premiumWon(points);
        long premium = perContract * quantity;
        long fee = DerivativePricing.fee(premium, DerivativePricing.OPTION_FEE_RATE);
        debit(userId, premium, fee, "옵션 프리미엄");
        String name = DerivativePricing.cycleLabel(cycleId) + " " + ("CALL".equals(type) ? "콜" : "풋")
                + " " + String.format(Locale.KOREA, "%,.0f", strike);
        String symbol = cycleId + "-" + ("CALL".equals(type) ? "C" : "P") + (long) strike;
        long id = insertPosition(userId, "OPTION", symbol, name, type, quantity, points, premium,
                0, 1, type, strike, Timestamp.from(expiry), premium);
        recordTrade(userId, id, "OPEN", "OPTION", symbol, type, quantity, points, -(premium + fee), fee);
        return new OrderResult(name + " " + quantity + "계약 매수 · 프리미엄 " + won(premium), account(userId));
    }

    @Transactional
    public OrderResult close(long userId, long positionId, int quantity) {
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY)
            throw new IllegalArgumentException("수량은 1~" + MAX_ORDER_QUANTITY + " 사이로 입력해 주세요.");
        lockUser(userId);
        PositionRow row = lockPosition(userId, positionId);
        if (row == null) throw new IllegalArgumentException("청산할 포지션을 찾을 수 없습니다.");
        if (!"OPEN".equals(row.status())) throw new IllegalArgumentException("이미 종료된 포지션입니다.");
        if (quantity > row.quantity())
            throw new IllegalArgumentException("보유 수량 " + row.quantity() + "계약을 초과했습니다.");
        return closeInternal(row, quantity, "CLOSE");
    }

    private OrderResult closeEtfBySymbol(long userId, String symbol, int quantity,
                                         List<StockRow> stocks, double index) {
        EtfDefinition definition = etfDefinition(symbol);
        List<PositionRow> rows = jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, status, opened_at, realized_pnl, mark_price, mark_value
                FROM derivative_positions
                WHERE user_id = ? AND kind = 'ETF' AND symbol = ? AND status = 'OPEN'
                ORDER BY id
                """, (rs, rowNumber) -> new PositionRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
                rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
                rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
                rs.getDouble("strike"), rs.getTimestamp("expiry_at"), rs.getString("status"),
                instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl"),
                rs.getDouble("mark_price"), rs.getLong("mark_value")), userId, definition.code());
        int remaining = quantity;
        OrderResult last = null;
        for (PositionRow row : rows) {
            if (remaining <= 0) break;
            int take = Math.min(remaining, row.quantity());
            last = closeInternal(row, take, "CLOSE");
            remaining -= take;
        }
        if (remaining > 0)
            throw new IllegalArgumentException(definition.name() + " 보유 수량이 부족합니다.");
        if (last == null) throw new IllegalArgumentException("보유한 ETF가 없습니다.");
        return last;
    }

    private OrderResult closeInternal(PositionRow row, int quantity, String action) {
        Instant now = clock.instant();
        List<StockRow> stocks = loadStocks();
        double index = weightedIndex(stocks);
        Map<String, Double> navs = etfNavs(stocks, index);
        long positionPortion = portion(row.entryNotional(), quantity, row.quantity());
        long marginPortion = portion(row.margin(), quantity, row.quantity());
        double price;
        long proceeds;
        long fee;
        long realized;
        long cashFlow;
        switch (row.kind()) {
            case "ETF" -> {
                price = navs.getOrDefault(row.symbol(), row.entryPrice());
                proceeds = Math.round(price * quantity);
                fee = DerivativePricing.fee(proceeds, DerivativePricing.ETF_FEE_RATE);
                realized = proceeds - positionPortion - fee;
                cashFlow = proceeds - fee;
            }
            case "FUTURE" -> {
                price = index;
                int direction = "SHORT".equals(row.side()) ? -1 : 1;
                long pnl = Math.round((price - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                        * quantity * direction);
                long equity = Math.max(0, marginPortion + pnl);
                fee = Math.min(equity, DerivativePricing.fee(
                        Math.round(price * DerivativePricing.CONTRACT_MULTIPLIER * quantity),
                        DerivativePricing.FUTURE_FEE_RATE));
                cashFlow = equity - fee;
                realized = cashFlow - marginPortion;
                proceeds = equity;
            }
            case "OPTION" -> {
                boolean call = "CALL".equals(row.optionType());
                Timestamp expiryAt = row.expiryAt();
                boolean expired = expiryAt == null || !expiryAt.toInstant().isAfter(now);
                double points = expired
                        ? DerivativePricing.intrinsic(index, row.strike(), call)
                        : DerivativePricing.optionPrice(index, row.strike(),
                        DerivativePricing.yearsLeft(expiryAt.toInstant(), now), currentVolatility(), call);
                price = points;
                proceeds = (expired
                        ? Math.round(points * DerivativePricing.CONTRACT_MULTIPLIER)
                        : DerivativePricing.premiumWon(points)) * quantity;
                fee = DerivativePricing.fee(proceeds, DerivativePricing.OPTION_FEE_RATE);
                realized = proceeds - positionPortion - fee;
                cashFlow = proceeds - fee;
            }
            default -> throw new IllegalArgumentException("지원하지 않는 포지션 종류입니다.");
        }
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", cashFlow, row.userId());
        int remaining = row.quantity() - quantity;
        if (remaining <= 0) {
            jdbc.update("""
                    UPDATE derivative_positions
                    SET quantity = 0, entry_notional = 0, margin = 0, mark_price = ?, mark_value = 0,
                        realized_pnl = realized_pnl + ?, status = ?, closed_at = ?, close_reason = ?
                    WHERE id = ?
                    """, price, realized, action, Timestamp.from(now), action, row.id());
        } else {
            long remainingValue = "FUTURE".equals(row.kind())
                    ? Math.max(0, row.margin() - marginPortion)
                    + Math.round((price - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                    * remaining * ("SHORT".equals(row.side()) ? -1 : 1))
                    : Math.round(price * remaining);
            jdbc.update("""
                    UPDATE derivative_positions
                    SET quantity = ?, entry_notional = entry_notional - ?, margin = margin - ?,
                        mark_price = ?, mark_value = ?, realized_pnl = realized_pnl + ?
                    WHERE id = ?
                    """, remaining, positionPortion, marginPortion, price, remainingValue, realized, row.id());
        }
        recordTrade(row.userId(), row.id(), action, row.kind(), row.symbol(), row.side(), quantity,
                price, cashFlow, fee);
        return new OrderResult(row.name() + " " + quantity + "계약 청산 · 정산 " + won(cashFlow), account(row.userId()));
    }

    // ------------------------------------------------------------ 만기·청산

    /**
     * 평가 갱신과 만기·강제청산. 여러 인스턴스가 실행해도 각 포지션을
     * 짧은 트랜잭션에서 잠그므로 같은 포지션을 두 번 결제하지 않는다.
     */
    @Scheduled(fixedDelayString = "${gamestock.derivatives.refresh-ms:3000}",
            initialDelayString = "${gamestock.derivatives.initial-delay-ms:5000}")
    public void refreshMarks() {
        try {
            refreshInternal();
        } catch (RuntimeException error) {
            log.warn("Derivative mark refresh failed: {}", error.toString());
        }
    }

    private void refreshInternal() {
        Instant now = clock.instant();
        List<StockRow> stocks = loadStocks();
        double index = weightedIndex(stocks);
        sample(index, now);
        series("FUTIDX", index);
        Map<String, Double> navs = etfNavs(stocks, index);
        for (EtfDefinition definition : ETFS) series("ETF:" + definition.code(),
                navs.getOrDefault(definition.code(), 1000.0));
        double volatility = currentVolatility();
        List<OpenRow> rows = jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, opened_at, realized_pnl
                FROM derivative_positions WHERE status = 'OPEN' ORDER BY id LIMIT 5000
                """, (rs, row) -> new OpenRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
                rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
                rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
                rs.getDouble("strike"), rs.getTimestamp("expiry_at"),
                instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl")));
        for (OpenRow row : rows) {
            long value;
            double mark;
            switch (row.kind()) {
                case "ETF" -> {
                    mark = navs.getOrDefault(row.symbol(), row.entryPrice());
                    value = Math.round(mark * row.quantity());
                }
                case "FUTURE" -> {
                    mark = index;
                    value = row.margin() + Math.round((mark - row.entryPrice())
                            * DerivativePricing.CONTRACT_MULTIPLIER * row.quantity()
                            * ("SHORT".equals(row.side()) ? -1 : 1));
                    if (value <= row.margin() * DerivativePricing.MAINTENANCE_RATE) {
                        settleInTransaction(() -> liquidate(row.id()));
                        continue;
                    }
                }
                case "OPTION" -> {
                    boolean call = "CALL".equals(row.optionType());
                    Instant expiry = row.expiryAt() == null ? now : row.expiryAt().toInstant();
                    if (!expiry.isAfter(now)) {
                        settleInTransaction(() -> expireOption(row.id()));
                        continue;
                    }
                    mark = DerivativePricing.optionPrice(index, row.strike(),
                            DerivativePricing.yearsLeft(expiry, now), volatility, call);
                    value = Math.max(10, Math.round(mark * DerivativePricing.CONTRACT_MULTIPLIER)) * row.quantity();
                }
                default -> {
                    continue;
                }
            }
            // 부분 청산과 겹치면 이전 수량 기준 평가액을 덮어쓰지 않는다.
            jdbc.update("""
                    UPDATE derivative_positions SET mark_price = ?, mark_value = ?
                    WHERE id = ? AND status = 'OPEN' AND quantity = ?
                    """, mark, value, row.id(), row.quantity());
        }
    }

    private void settleInTransaction(Runnable action) {
        try {
            transactions.executeWithoutResult(status -> action.run());
        } catch (RuntimeException error) {
            log.debug("Derivative settlement skipped: {}", error.toString());
        }
    }

    private void expireOption(long positionId) {
        OpenRow candidate = findOpenPosition(positionId);
        if (candidate == null || !"OPTION".equals(candidate.kind())) return;
        lockUser(candidate.userId());
        OpenRow row = lockOpenPosition(positionId);
        if (row == null || !"OPTION".equals(row.kind())) return;
        Instant now = clock.instant();
        if (row.expiryAt() != null && row.expiryAt().toInstant().isAfter(now)) return;
        double index = weightedIndex(loadStocks());
        boolean call = "CALL".equals(row.optionType());
        double points = DerivativePricing.intrinsic(index, row.strike(), call);
        long proceeds = Math.round(points * DerivativePricing.CONTRACT_MULTIPLIER) * row.quantity();
        long realized = proceeds - row.entryNotional();
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", proceeds, row.userId());
        jdbc.update("""
                UPDATE derivative_positions SET status = 'EXPIRED', quantity = 0, mark_price = ?, mark_value = 0,
                  realized_pnl = realized_pnl + ?, closed_at = ?, close_reason = 'EXPIRY' WHERE id = ?
                """, points, realized, Timestamp.from(now), row.id());
        recordTrade(row.userId(), row.id(), "EXPIRE", "OPTION", row.symbol(), row.side(), row.quantity(),
                points, proceeds, 0);
    }

    private void liquidate(long positionId) {
        OpenRow candidate = findOpenPosition(positionId);
        if (candidate == null || !"FUTURE".equals(candidate.kind())) return;
        lockUser(candidate.userId());
        OpenRow row = lockOpenPosition(positionId);
        if (row == null || !"FUTURE".equals(row.kind())) return;
        double index = weightedIndex(loadStocks());
        long pnl = Math.round((index - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                * row.quantity() * ("SHORT".equals(row.side()) ? -1 : 1));
        long equity = row.margin() + pnl;
        if (equity > row.margin() * DerivativePricing.MAINTENANCE_RATE) return;
        long refund = Math.max(0, equity);
        jdbc.update("UPDATE users SET cash = cash + ? WHERE id = ?", refund, row.userId());
        jdbc.update("""
                UPDATE derivative_positions SET status = 'LIQUIDATED', quantity = 0, mark_price = ?, mark_value = 0,
                  realized_pnl = realized_pnl + ?, closed_at = ?, close_reason = 'LIQUIDATION' WHERE id = ?
                """, index, refund - row.margin(), Timestamp.from(clock.instant()), row.id());
        recordTrade(row.userId(), row.id(), "LIQUIDATE", "FUTURE", row.symbol(), row.side(), row.quantity(),
                index, refund, 0);
    }

    // ------------------------------------------------------------ 저장 도우미

    private void lockUser(long userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
    }

    private PositionRow lockPosition(long userId, long positionId) {
        List<PositionRow> rows = jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, status, opened_at, realized_pnl, mark_price, mark_value
                FROM derivative_positions WHERE id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> new PositionRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
                rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
                rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
                rs.getDouble("strike"), rs.getTimestamp("expiry_at"), rs.getString("status"),
                instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl"),
                rs.getDouble("mark_price"), rs.getLong("mark_value")), positionId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OpenRow findOpenPosition(long positionId) {
        List<OpenRow> rows = jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, opened_at, realized_pnl
                FROM derivative_positions WHERE id = ? AND status = 'OPEN'
                """, (rs, row) -> new OpenRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
                rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
                rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
                rs.getDouble("strike"), rs.getTimestamp("expiry_at"),
                instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl")), positionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OpenRow lockOpenPosition(long positionId) {
        List<OpenRow> rows = jdbc.query("""
                SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                       leverage, option_type, strike, expiry_at, opened_at, realized_pnl
                FROM derivative_positions WHERE id = ? AND status = 'OPEN' FOR UPDATE
                """, (rs, row) -> new OpenRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
                rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
                rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
                rs.getDouble("strike"), rs.getTimestamp("expiry_at"),
                instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl")), positionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void debit(long userId, long amount, long fee, String label) {
        long required = amount + fee;
        Long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        if (cash == null || cash < required)
            throw new IllegalArgumentException(label + "이(가) 부족합니다. 필요 " + won(required)
                    + " · 보유 " + won(cash == null ? 0 : cash));
        jdbc.update("UPDATE users SET cash = cash - ? WHERE id = ?", required, userId);
    }

    private long insertPosition(long userId, String kind, String symbol, String name, String side,
                                int quantity, double entryPrice, long entryNotional, long margin,
                                int leverage, String optionType, double strike, Timestamp expiryAt,
                                long markValue) {
        jdbc.update("""
                INSERT INTO derivative_positions
                  (user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin, leverage,
                   option_type, strike, expiry_at, status, mark_price, mark_value)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                """, userId, kind, symbol, name, side, quantity, entryPrice, entryNotional, margin, leverage,
                optionType, strike, expiryAt, entryPrice, markValue);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void recordTrade(long userId, long positionId, String action, String kind, String symbol,
                             String side, int quantity, double price, long cashFlow, long fee) {
        jdbc.update("""
                INSERT INTO derivative_trades
                  (user_id, position_id, action, kind, symbol, side, quantity, price, cash_flow, fee)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, positionId, action, kind, symbol, side, quantity, price, cashFlow, fee);
    }

    private static long portion(long total, int quantity, int originalQuantity) {
        if (originalQuantity <= 0) return 0;
        if (quantity >= originalQuantity) return total;
        return Math.round(total * (double) quantity / originalQuantity);
    }

    private static EtfDefinition etfDefinition(String symbol) {
        String code = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        return ETFS.stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("상장되지 않은 ETF입니다."));
    }

    private static String normalized(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "를 확인해 주세요.");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ 변동성·시계열

    private void sample(double index, Instant now) {
        synchronized (samples) {
            double[] last = samples.peekLast();
            if (last != null && now.getEpochSecond() - (long) last[0] < 1) return;
            samples.addLast(new double[]{now.getEpochSecond(), index});
            while (samples.size() > SAMPLE_LIMIT) samples.removeFirst();
        }
    }

    private double currentVolatility() {
        List<double[]> copy;
        synchronized (samples) {
            copy = new ArrayList<>(samples);
        }
        return DerivativePricing.realizedVolatility(copy);
    }

    private List<Double> series(String key, double value) {
        Deque<Double> values = series.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (values) {
            if (values.isEmpty() || Double.compare(values.peekLast(), value) != 0) values.addLast(value);
            while (values.size() > SERIES_LIMIT) values.removeFirst();
            return new ArrayList<>(values);
        }
    }

    private static String instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static String won(long value) {
        return String.format(Locale.KOREA, "%,d원", value);
    }
}
