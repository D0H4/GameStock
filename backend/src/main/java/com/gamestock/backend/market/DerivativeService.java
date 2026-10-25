package com.gamestock.backend.market;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

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
    private static final int MAX_ORDER_QUANTITY = 100;
    private static final int MAX_OPEN_POSITIONS = 60;
    private static final int SERIES_LIMIT = 120;
    private static final long MAX_ACCOUNT_EXPOSURE = 10_000_000;
    private static final String FUTURE_CODE = "FUTIDX";

    private record OpenRow(long id, long userId, String kind, String symbol, String name, String side,
                           int quantity, double entryPrice, long entryNotional, long margin, int leverage,
                           String optionType, double strike, Timestamp expiryAt, String openedAt,
                           long realizedPnl) { }

    private record PositionRow(long id, long userId, String kind, String symbol, String name, String side,
                               int quantity, double entryPrice, long entryNotional, long margin, int leverage,
                               String optionType, double strike, Timestamp expiryAt, String status,
                               String openedAt, long realizedPnl, double markPrice, long markValue) { }

    private static final List<DerivativeBaskets.Definition> ETFS = DerivativeBaskets.DEFINITIONS;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private Clock clock = Clock.systemUTC();
    private final DerivativeIndexHistory indexHistory;
    private Instant nextHistoryRetention=Instant.EPOCH;
    void setClock(Clock clock) { this.clock = clock; }
    private final DerivativePriceOracle oracle;
    private final DerivativeBaskets baskets;
    private final Map<String, Deque<Double>> series = new ConcurrentHashMap<>();

    public DerivativeService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.indexHistory = new DerivativeIndexHistory(jdbc);
        this.oracle = new DerivativePriceOracle(jdbc);
        this.baskets = new DerivativeBaskets(jdbc);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // Production migration is serialized by MarketSchema; tests may initialize explicitly.
    public void ensureTables() {
        ensureTables(jdbc);
    }

    /** 스키마 마이그레이션에서도 호출한다. 여러 번 실행해도 안전하다. */
    static void ensureTables(JdbcTemplate jdbc) {
        AdminSchema.ensureRuntime(jdbc);
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
                  mark_version BIGINT NOT NULL DEFAULT 0,
                  realized_pnl BIGINT NOT NULL DEFAULT 0,
                  opened_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  closed_at TIMESTAMP(3) NULL,
                  close_reason VARCHAR(16) NULL,
                  INDEX ix_derivative_user (user_id, status),
                  INDEX ix_derivative_expiry (status, expiry_at),
                  CONSTRAINT fk_derivative_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_positions' AND column_name='mark_version'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE derivative_positions ADD COLUMN mark_version BIGINT NOT NULL DEFAULT 0");
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
                  cash_flow_version TINYINT NOT NULL DEFAULT 2,
                  fee BIGINT NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  INDEX ix_derivative_trades_user (user_id, id),
                  CONSTRAINT fk_derivative_trade_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        Integer versionColumn = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_trades' AND column_name='cash_flow_version'", Integer.class);
        if (versionColumn == 0) jdbc.execute("ALTER TABLE derivative_trades ADD COLUMN cash_flow_version TINYINT NOT NULL DEFAULT 1");
        // Version guard makes the legacy ETF fee backfill safe to resume/re-run.
        jdbc.update("UPDATE derivative_trades SET cash_flow=cash_flow-fee,cash_flow_version=2 WHERE cash_flow_version=1 AND kind='ETF' AND action='OPEN'");
        jdbc.update("UPDATE derivative_trades SET cash_flow_version=2 WHERE cash_flow_version=1");
        String versionDefault=jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='derivative_trades' AND column_name='cash_flow_version'",String.class);
        if(!"2".equals(versionDefault))jdbc.execute("ALTER TABLE derivative_trades ALTER COLUMN cash_flow_version SET DEFAULT 2");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='derivative_trades' AND index_name='ix_derivative_trades_flow'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE derivative_trades ADD INDEX ix_derivative_trades_flow (kind, created_at)");
        DerivativeIndexHistory.ensureTables(jdbc);
        DerivativePriceOracle.ensureTables(jdbc);
        DerivativeBaskets.ensureTables(jdbc);
        // Freeze legacy references during the locked migration, before startup rolls the day.
        new DerivativeIndexHistory(jdbc).initializeBases();
        new DerivativePriceOracle(jdbc).initialize();
        new DerivativeBaskets(jdbc).rebalance();
    }

    // ------------------------------------------------------------ 공개 시세

    public DerivativeSnapshot snapshot() {
        Instant now = clock.instant();
        DerivativeBaskets.Values prices = loadPrices();
        double index = prices.index();
        double previous = prices.basket("ETFIDX").previous();
        List<EtfQuote> etfs = new ArrayList<>(ETFS.size());
        for (DerivativeBaskets.Definition definition : ETFS) etfs.add(etfQuote(definition, prices, index));
        FutureQuote future = new FutureQuote(FUTURE_CODE, "게임주 지수 선물", index, previous,
                percent(index, previous), DerivativePricing.CONTRACT_MULTIPLIER, DerivativePricing.LEVERAGES,
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
        return new DerivativeSnapshot(index, previous, percent(index, previous), etfs, future, cycles,
                contracts, now.toString());
    }

    private EtfQuote etfQuote(DerivativeBaskets.Definition definition, DerivativeBaskets.Values prices, double index) {
        var basket = prices.basket(definition.code());
        List<Constituent> constituents = basket.members().stream()
                .map(stock -> new Constituent(stock.code(), stock.name(), stock.weight(), stock.changePercent()))
                .sorted(Comparator.comparingDouble(Constituent::weight).reversed()).toList();
        double nav = basket.price(), previous = basket.previous();
        return new EtfQuote(definition.code(), definition.name(), definition.theme(), definition.description(),
                nav, previous, percent(nav, previous), constituents, series("ETF:" + definition.code(), nav));
    }

    private DerivativeBaskets.Values loadPrices() { return baskets.load(); }

    private boolean fresh(DerivativeBaskets.Values prices, String symbol, Instant now) {
        return prices.basket(symbol).fresh(now);
    }

    private void requireFresh(DerivativeBaskets.Values prices, String symbol) {
        if (!fresh(prices, symbol, clock.instant()))
            throw new IllegalArgumentException("기준가격을 확인 중입니다. 충분한 체결이 쌓인 뒤 다시 거래해 주세요.");
    }

    private static String basketCode(String kind, String symbol) {
        return "ETF".equals(kind) ? etfDefinition(symbol).code() : "ETFIDX";
    }

    private void checkExposure(long user, long additional, DerivativeBaskets.Values prices) {
        double index = prices.index();
        Map<String, Double> navs = etfNavs(prices, index);
        double existing = 0;
        for (PositionRow row : positionRows(user)) {
            // Adding risk requires a current valuation of the holdings as well as the new product.
            // Closing positions does not use this check, so stale unrelated assets cannot trap exits.
            requireFresh(prices, basketCode(row.kind(), row.symbol()));
            existing += switch (row.kind()) {
                case "ETF" -> Math.max(row.entryNotional(), row.quantity() * navs.get(row.symbol()));
                case "FUTURE" -> Math.max(row.entryNotional(), row.quantity() * index * DerivativePricing.CONTRACT_MULTIPLIER);
                case "OPTION" -> row.quantity() * Math.max(row.strike(), index) * DerivativePricing.CONTRACT_MULTIPLIER;
                default -> row.entryNotional();
            };
        }
        if (existing + additional > MAX_ACCOUNT_EXPOSURE)
            throw new IllegalArgumentException("계좌의 파생상품 총 명목금액 한도는 " + won(MAX_ACCOUNT_EXPOSURE) + "입니다.");
    }

    private static double percent(double current, double reference) {
        return reference <= 0 ? 0 : Math.round((current - reference) * 10_000.0 / reference) / 100.0;
    }

    // ------------------------------------------------------------ 계좌

    @Transactional
    public Account account(long userId) {
        long cash = jdbc.queryForObject("SELECT cash FROM users WHERE id = ?", Long.class, userId);
        List<PositionRow> rows = positionRows(userId);
        Instant now = clock.instant();
        DerivativeBaskets.Values prices = loadPrices();
        double index = prices.index();
        Map<String, Double> navs = etfNavs(prices, index);
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
                value = Math.max(0, row.margin() + pnl);
            }
            case "OPTION" -> {
                boolean call = "CALL".equals(row.optionType());
                Instant expiry = row.expiryAt() == null ? now : row.expiryAt().toInstant();
                boolean expired = !expiry.isAfter(now);
                if (expired) {
                    var fixing = row.expiryAt() == null ? java.util.OptionalDouble.empty() : indexHistory.existingFixing(expiry);
                    // A pending expiry retains its last stored valuation. Account reads cannot
                    // invent a fixing or roll back an otherwise valid trade in another product.
                    mark = fixing.isPresent() ? DerivativePricing.intrinsic(fixing.getAsDouble(), row.strike(), call) : row.markPrice();
                    value = fixing.isPresent() ? Math.round(mark * DerivativePricing.CONTRACT_MULTIPLIER) * row.quantity() : row.markValue();
                } else {
                    mark = DerivativePricing.optionPrice(index, row.strike(),
                            DerivativePricing.yearsLeft(expiry, now), volatility, call);
                    value = DerivativePricing.premiumWon(mark) * row.quantity();
                }
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

    private Map<String, Double> etfNavs(DerivativeBaskets.Values prices, double index) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (DerivativeBaskets.Definition definition : ETFS) result.put(definition.code(), prices.basket(definition.code()).price());
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
        requireTradingEnabled();
        lockUser(userId);
        int openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM derivative_positions WHERE user_id = ? AND status = 'OPEN'", Integer.class, userId);
        if (openCount >= MAX_OPEN_POSITIONS && !("ETF".equals(kind) && "SELL".equals(side)))
            throw new IllegalArgumentException("동시에 보유할 수 있는 파생 포지션은 " + MAX_OPEN_POSITIONS + "개까지입니다.");

        DerivativeBaskets.Values prices = loadPrices();
        requireFresh(prices, basketCode(kind, request.symbol()));
        // Exposure always uses the higher current valuation, regardless of the new order's side.
        DerivativeBaskets.Values exposurePrices = prices.execution(true);
        boolean higher = "BUY".equals(side) || "LONG".equals(side) || "CALL".equals(side);
        prices = prices.execution(higher);
        double index = prices.index();
        Instant now = clock.instant();
        if ("ETF".equals(kind)) {
            if (!"BUY".equals(side) && !"SELL".equals(side))
                throw new IllegalArgumentException("ETF 방향은 BUY 또는 SELL입니다.");
            if ("SELL".equals(side)) return closeEtfBySymbol(userId, request.symbol(), quantity, prices, index);
            return openEtf(userId, request.symbol(), quantity, prices, index, exposurePrices);
        }
        if ("FUTURE".equals(kind)) return openFuture(userId, side, quantity, request.leverage(), index, exposurePrices);
        if ("OPTION".equals(kind)) return openOption(userId, request, quantity, index, now, exposurePrices);
        throw new IllegalArgumentException("상품 종류는 ETF, FUTURE, OPTION 중 하나입니다.");
    }

    private OrderResult openEtf(long userId, String symbol, int quantity, DerivativeBaskets.Values prices, double index,
                               DerivativeBaskets.Values exposurePrices) {
        DerivativeBaskets.Definition definition = etfDefinition(symbol);
        double nav = etfNavs(prices, index).getOrDefault(definition.code(), 1000.0);
        long amount = Math.round(nav * quantity);
        long fee = DerivativePricing.fee(amount, DerivativePricing.ETF_FEE_RATE);
        checkExposure(userId, amount, exposurePrices);
        debit(userId, amount, fee, "ETF 매수 금액");
        long id = insertPosition(userId, "ETF", definition.code(), definition.name(), "LONG", quantity,
                nav, amount, 0, 1, null, 0, null, amount, prices.version());
        recordTrade(userId, id, "OPEN", "ETF", definition.code(), "LONG", quantity, nav, -(amount + fee), fee);
        return new OrderResult(definition.name() + " " + quantity + "좌 매수 · " + won(amount + fee), account(userId));
    }

    private OrderResult openFuture(long userId, String side, int quantity, Integer requestedLeverage, double index,
                                  DerivativeBaskets.Values exposurePrices) {
        if (!"LONG".equals(side) && !"SHORT".equals(side))
            throw new IllegalArgumentException("선물 방향은 LONG 또는 SHORT입니다.");
        int leverage = requestedLeverage == null ? 5 : requestedLeverage;
        if (!DerivativePricing.LEVERAGES.contains(leverage))
            throw new IllegalArgumentException("레버리지는 " + DerivativePricing.LEVERAGES + " 중에서 선택해 주세요.");
        long notional = DerivativePricing.contractNotional(index, quantity);
        long margin = (long) Math.ceil(notional / (double) leverage);
        long fee = DerivativePricing.fee(notional, DerivativePricing.FUTURE_FEE_RATE);
        checkExposure(userId, DerivativePricing.contractNotional(exposurePrices.index(), quantity), exposurePrices);
        debit(userId, margin, fee, "선물 증거금");
        long id = insertPosition(userId, "FUTURE", FUTURE_CODE, "게임주 지수 선물 " + leverage + "x",
                side, quantity, index, notional, margin, leverage, null, 0, null, margin, exposurePrices.version());
        recordTrade(userId, id, "OPEN", "FUTURE", FUTURE_CODE, side, quantity, index, -(margin + fee), fee);
        return new OrderResult("지수 선물 " + ("LONG".equals(side) ? "매수" : "매도") + " " + quantity
                + "계약 · 증거금 " + won(margin), account(userId));
    }

    private OrderResult openOption(long userId, OrderRequest request, int quantity, double index, Instant now,
                                  DerivativeBaskets.Values exposurePrices) {
        String cycleId = normalized(request.cycleId(), "만기 회차");
        if (!DerivativePricing.cycleIds().contains(cycleId))
            throw new IllegalArgumentException("지원하지 않는 옵션 만기 회차입니다.");
        String type = normalized(request.side(), "옵션 종류");
        if (!"CALL".equals(type) && !"PUT".equals(type))
            throw new IllegalArgumentException("옵션 종류는 CALL 또는 PUT입니다.");
        if (request.strike() == null) throw new IllegalArgumentException("행사가를 선택해 주세요.");
        double strike = request.strike();
        if (!Double.isFinite(strike) || strike <= 0 || Math.abs(strike / index - 1) > 0.06)
            throw new IllegalArgumentException("현재 지수 ±6% 안의 행사가만 거래할 수 있습니다.");
        Instant expiry = DerivativePricing.expiryFor(cycleId, now);
        if (request.expiryEpoch() != null && request.expiryEpoch() != expiry.getEpochSecond())
            throw new IllegalArgumentException("만기가 지난 옵션입니다. 새 회차를 선택해 주세요.");
        double points = DerivativePricing.optionPrice(index, strike,
                DerivativePricing.yearsLeft(expiry, now), currentVolatility(), "CALL".equals(type));
        long perContract = DerivativePricing.premiumWon(points);
        long premium = perContract * quantity;
        long fee = DerivativePricing.fee(premium, DerivativePricing.OPTION_FEE_RATE);
        checkExposure(userId, DerivativePricing.contractNotional(Math.max(exposurePrices.index(), strike), quantity), exposurePrices);
        debit(userId, premium, fee, "옵션 프리미엄");
        String name = DerivativePricing.cycleLabel(cycleId) + " " + ("CALL".equals(type) ? "콜" : "풋")
                + " " + String.format(Locale.KOREA, "%,.0f", strike);
        String symbol = cycleId + "-" + ("CALL".equals(type) ? "C" : "P") + (long) strike;
        long id = insertPosition(userId, "OPTION", symbol, name, type, quantity, points, premium,
                0, 1, type, strike, Timestamp.from(expiry), premium, exposurePrices.version());
        recordTrade(userId, id, "OPEN", "OPTION", symbol, type, quantity, points, -(premium + fee), fee);
        return new OrderResult(name + " " + quantity + "계약 매수 · 프리미엄 " + won(premium), account(userId));
    }

    @Transactional
    public OrderResult close(long userId, long positionId, int quantity) {
        if (quantity < 1 || quantity > MAX_ORDER_QUANTITY)
            throw new IllegalArgumentException("수량은 1~" + MAX_ORDER_QUANTITY + " 사이로 입력해 주세요.");
        requireTradingEnabled();
        lockUser(userId);
        PositionRow row = lockPosition(userId, positionId);
        if (row == null) throw new IllegalArgumentException("청산할 포지션을 찾을 수 없습니다.");
        if (!"OPEN".equals(row.status())) throw new IllegalArgumentException("이미 종료된 포지션입니다.");
        if (quantity > row.quantity())
            throw new IllegalArgumentException("보유 수량 " + row.quantity() + "계약을 초과했습니다.");
        if ("OPTION".equals(row.kind()) && row.expiryAt() != null && !row.expiryAt().toInstant().isAfter(clock.instant())) {
            expireOption(positionId);
            return new OrderResult("만기 옵션을 정산했습니다.", account(userId));
        }
        return closeInternal(row, quantity, "CLOSE");
    }

    /**
     * A collateral sale rechecks its cash shortfall only after obtaining the account and position locks.
     * The loan service supplies the fee/slippage buffer and may accrue interest under the same lock.
     * Returns true only when a successful partial close leaves contracts for another attempt.
     */
    @Transactional
    public boolean closeForCollateral(long userId, long positionId, LongSupplier bufferedCashNeeded) {
        requireTradingEnabled();
        lockUser(userId);
        PositionRow row = lockPosition(userId, positionId);
        if (row == null || !"OPEN".equals(row.status()) || row.quantity() <= 0) return false;
        long needed = bufferedCashNeeded.getAsLong();
        if (needed <= 0) return false;
        if ("OPTION".equals(row.kind()) && row.expiryAt() != null && !row.expiryAt().toInstant().isAfter(clock.instant())) {
            expireOption(positionId);
            return false;
        }
        // Marks estimate the size; closeInternal still uses the current protected execution price.
        // If proceeds fall short, the caller can retry the remaining contracts with their new mark.
        int quantity = Math.min(row.quantity(), MAX_ORDER_QUANTITY);
        if (row.markValue() > 0)
            quantity = (int) Math.min(quantity, Math.max(1, Math.ceil(needed * (double) row.quantity() / row.markValue())));
        closeInternal(row, quantity, "CLOSE");
        return quantity < row.quantity();
    }

    private OrderResult closeEtfBySymbol(long userId, String symbol, int quantity,
                                         DerivativeBaskets.Values prices, double index) {
        DerivativeBaskets.Definition definition = etfDefinition(symbol);
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
        DerivativeBaskets.Values prices = loadPrices();
        requireFresh(prices, basketCode(row.kind(), row.symbol()));
        prices = prices.execution("SHORT".equals(row.side()) || "PUT".equals(row.optionType()));
        double index = prices.index();
        Map<String, Double> navs = etfNavs(prices, index);
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
                if (expiryAt == null) throw new IllegalStateException("옵션 만기 정보가 없어 정산을 보류합니다.");
                boolean expired = !expiryAt.toInstant().isAfter(now);
                double points = expired
                        ? DerivativePricing.intrinsic(indexHistory.fixing(expiryAt.toInstant()), row.strike(), call)
                        : DerivativePricing.optionPrice(index, row.strike(),
                        DerivativePricing.yearsLeft(expiryAt.toInstant(), now), currentVolatility(), call);
                price = points;
                proceeds = (expired
                        ? Math.round(points * DerivativePricing.CONTRACT_MULTIPLIER)
                        : DerivativePricing.premiumWon(points)) * quantity;
                fee = expired ? 0 : DerivativePricing.fee(proceeds, DerivativePricing.OPTION_FEE_RATE);
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
            long remainingValue;
            if ("FUTURE".equals(row.kind())) {
                remainingValue = Math.max(0, row.margin() - marginPortion)
                        + Math.round((price - row.entryPrice()) * DerivativePricing.CONTRACT_MULTIPLIER
                        * remaining * ("SHORT".equals(row.side()) ? -1 : 1));
            } else if ("OPTION".equals(row.kind())) {
                boolean expired = row.expiryAt() == null || !row.expiryAt().toInstant().isAfter(now);
                remainingValue = (expired ? Math.round(price * DerivativePricing.CONTRACT_MULTIPLIER)
                        : DerivativePricing.premiumWon(price)) * remaining;
            } else {
                remainingValue = Math.round(price * remaining);
            }
            jdbc.update("""
                    UPDATE derivative_positions
                    SET quantity = ?, entry_notional = entry_notional - ?, margin = margin - ?,
                        mark_price = ?, mark_value = ?, mark_version = GREATEST(mark_version, ?), realized_pnl = realized_pnl + ?
                    WHERE id = ?
                    """, remaining, positionPortion, marginPortion, price, remainingValue, prices.version(), realized, row.id());
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
    public synchronized void refreshMarks() {
        try {
            refreshInternal();
            if(!clock.instant().isBefore(nextHistoryRetention)) {
                indexHistory.retain(clock.instant());
                nextHistoryRetention=clock.instant().plusSeconds(3600);
            }
        } catch (RuntimeException error) {
            log.warn("Derivative mark refresh failed: {}", error.toString());
        }
    }

    private void refreshInternal() {
        Instant now = clock.instant();
        DerivativeBaskets.Values prices = transactions.execute(status -> {
            baskets.lock();
            indexHistory.initializeBases();
            oracle.refresh(now);
            baskets.rebalance();
            baskets.advanceValuationVersion();
            // Current reads keep a rejected oracle CAS from promoting its old MVCC snapshot
            // into a fresh index sample or position mark. Reference locks last through commit.
            DerivativeBaskets.Values observed = baskets.loadCurrent();
            // 차트용 표본은 매번 남기고, 모든 구성 종목이 신선할 때만 정산에 쓸 수 있다고 표시한다.
            double observedIndex = observed.index();
            if (Double.isFinite(observedIndex) && observedIndex > 0)
                indexHistory.observe(observedIndex, now, fresh(observed, "ETFIDX", now));
            return observed;
        });
        double index = prices.index();
        series("FUTIDX", index);
        Map<String, Double> navs = etfNavs(prices, index);
        for (DerivativeBaskets.Definition definition : ETFS) series("ETF:" + definition.code(),
                navs.getOrDefault(definition.code(), 1000.0));
        double volatility = currentVolatility();
        Long highWater = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM derivative_positions", Long.class);
        long cursor = 0;
        while (cursor < highWater) {
            List<OpenRow> rows = jdbc.query("""
                    SELECT id, user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin,
                           leverage, option_type, strike, expiry_at, opened_at, realized_pnl
                    FROM derivative_positions WHERE status = 'OPEN' AND id>? AND id<=? ORDER BY id LIMIT 500
                    """, (rs, row) -> new OpenRow(rs.getLong("id"), rs.getLong("user_id"),
                    rs.getString("kind"), rs.getString("symbol"), rs.getString("name"), rs.getString("side"),
                    rs.getInt("quantity"), rs.getDouble("entry_price"), rs.getLong("entry_notional"),
                    rs.getLong("margin"), rs.getInt("leverage"), rs.getString("option_type"),
                    rs.getDouble("strike"), rs.getTimestamp("expiry_at"),
                    instant(rs.getTimestamp("opened_at")), rs.getLong("realized_pnl")), cursor, highWater);
            if (rows.isEmpty()) break;
            cursor = rows.get(rows.size()-1).id();
            for (OpenRow row : rows) {
                if ("OPTION".equals(row.kind()) && row.expiryAt() != null && !row.expiryAt().toInstant().isAfter(now)) {
                    settleInTransaction(() -> expireOption(row.id()));
                    continue;
                }
                if (!fresh(prices, basketCode(row.kind(), row.symbol()), clock.instant())) continue;
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
                            settleInTransaction(() -> liquidate(row.userId(), row.id()));
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
                // A delayed instance must not overwrite a newer mark, a position opened
                // after its price snapshot, or a partial close using that same snapshot.
                jdbc.update("""
                        UPDATE derivative_positions SET mark_price = ?, mark_value = ?, mark_version = ?
                        WHERE id = ? AND status = 'OPEN' AND quantity = ? AND mark_version < ?
                        """, mark, value, prices.version(), row.id(), row.quantity(), prices.version());
            }
        }
    }

    private void settleInTransaction(Runnable action) {
        try {
            transactions.executeWithoutResult(status -> action.run());
        } catch (RuntimeException error) {
            log.warn("Derivative settlement deferred: {}", error.toString());
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
        double index = indexHistory.fixing(row.expiryAt().toInstant());
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

    private void liquidate(long userId, long positionId) {
        // The scheduler already knows the owner. Do not start an MVCC snapshot by
        // looking it up before waiting for the account lock. In this short transaction,
        // loadPrices() must be the first consistent read, after both locking reads.
        lockUser(userId);
        OpenRow row = lockOpenPosition(positionId);
        if (row == null || row.userId() != userId || !"FUTURE".equals(row.kind())) return;
        DerivativeBaskets.Values prices = loadPrices();
        requireFresh(prices, "ETFIDX");
        double index = prices.index();
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

    private void requireTradingEnabled() {
        // Current shared read held until commit: an admin pause waits for admitted trades,
        // while concurrent orders remain independent. Acquire before any user/position lock.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT market_paused FROM admin_runtime WHERE id=1 FOR SHARE", Boolean.class)))
            throw new IllegalArgumentException("운영 점검 중에는 파생상품 주문과 청산이 중단됩니다.");
    }

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
                                long markValue, long markVersion) {
        jdbc.update("""
                INSERT INTO derivative_positions
                  (user_id, kind, symbol, name, side, quantity, entry_price, entry_notional, margin, leverage,
                   option_type, strike, expiry_at, status, mark_price, mark_value, mark_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """, userId, kind, symbol, name, side, quantity, entryPrice, entryNotional, margin, leverage,
                optionType, strike, expiryAt, entryPrice, markValue, markVersion);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void recordTrade(long userId, long positionId, String action, String kind, String symbol,
                             String side, int quantity, double price, long cashFlow, long fee) {
        jdbc.update("""
                INSERT INTO derivative_trades
                  (user_id, position_id, action, kind, symbol, side, quantity, price, cash_flow, fee, cash_flow_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 2)
                """, userId, positionId, action, kind, symbol, side, quantity, price, cashFlow, fee);
    }

    private static long portion(long total, int quantity, int originalQuantity) {
        if (originalQuantity <= 0) return 0;
        if (quantity >= originalQuantity) return total;
        return Math.round(total * (double) quantity / originalQuantity);
    }

    private static DerivativeBaskets.Definition etfDefinition(String symbol) {
        String code = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        return ETFS.stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("상장되지 않은 ETF입니다."));
    }

    private static String normalized(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "를 확인해 주세요.");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ 옵션 거래 흐름

    private static final Map<String, Long> FLOW_RANGES = Map.of("1h", 3_600L, "6h", 21_600L, "1d", 86_400L, "1w", 604_800L);
    private static final Map<String, Integer> FLOW_INTERVALS = Map.of("1m", 60, "5m", 300, "15m", 900, "1h", 3_600);
    private static final int MAX_FLOW_BUCKETS = 400;
    private static final long FLOW_CACHE_MILLIS = 5_000;
    private final Map<String, Map.Entry<Long, OptionFlow>> flowCache = new ConcurrentHashMap<>();

    /**
     * 기간을 봉 간격으로 나눠 콜·풋 체결 수량과 프리미엄, 봉마다 마지막 지수를 돌려준다.
     * 신규(OPEN)와 청산(CLOSE)만 체결로 세고, 만기 결제(EXPIRE)는 거래가 아니라 뺀다.
     * 봉 번호는 바인딩한 시작 시각과의 차이로 센다. UNIX_TIMESTAMP는 DB 세션 시간대를 타서 JVM 시각과 어긋날 수 있다.
     * 공개 시세라 같은 조건은 5초 동안 같은 결과를 재사용해 DB를 반복해서 훑지 않는다.
     */
    public OptionFlow optionFlow(String range, String interval) {
        Long span = FLOW_RANGES.get(range);
        Integer step = FLOW_INTERVALS.get(interval);
        if (span == null) throw new IllegalArgumentException("기간은 1h, 6h, 1d, 1w 중 하나입니다.");
        if (step == null) throw new IllegalArgumentException("봉 간격은 1m, 5m, 15m, 1h 중 하나입니다.");
        if (span / step > MAX_FLOW_BUCKETS) throw new IllegalArgumentException("기간에 비해 봉 간격이 너무 짧습니다.");
        long nowMillis = clock.millis();
        String key = range + "/" + interval;
        Map.Entry<Long, OptionFlow> cached = flowCache.get(key);
        if (cached != null && nowMillis - cached.getKey() < FLOW_CACHE_MILLIS) return cached.getValue();

        long to = (nowMillis / 1000 / step + 1) * step;
        int count = (int) ((span + step - 1) / step);
        long from = to - (long) count * step;
        Timestamp start = Timestamp.from(Instant.ofEpochSecond(from));
        Timestamp end = Timestamp.from(Instant.ofEpochSecond(to));
        long[] callVolume = new long[count], putVolume = new long[count];
        long[] callPremium = new long[count], putPremium = new long[count], netPremium = new long[count];
        Double[] index = new Double[count];

        jdbc.query("""
                SELECT FLOOR(TIMESTAMPDIFF(SECOND, ?, created_at) / ?) AS slot, side, action, SUM(quantity) AS volume,
                  SUM(CASE WHEN action = 'OPEN' THEN -cash_flow - fee ELSE cash_flow + fee END) AS premium
                FROM derivative_trades
                WHERE kind = 'OPTION' AND action IN ('OPEN', 'CLOSE') AND created_at >= ? AND created_at < ?
                GROUP BY slot, side, action
                """, rs -> {
            int slot = rs.getInt("slot");
            if (slot < 0 || slot >= count) return;
            boolean call = "CALL".equals(rs.getString("side"));
            boolean open = "OPEN".equals(rs.getString("action"));
            long volume = rs.getLong("volume");
            long premium = rs.getLong("premium");
            if (call) { callVolume[slot] += volume; callPremium[slot] += premium; }
            else { putVolume[slot] += volume; putPremium[slot] += premium; }
            // 콜 매수·풋 청산은 상승 쪽, 풋 매수·콜 청산은 하락 쪽 돈이다.
            netPremium[slot] += call == open ? premium : -premium;
        }, start, step, start, end);

        jdbc.query("""
                SELECT FLOOR(TIMESTAMPDIFF(SECOND, ?, o.observed_at) / ?) AS slot, o.index_value
                FROM derivative_index_observations o JOIN (
                  SELECT MAX(observed_at) AS at FROM derivative_index_observations
                  WHERE observed_at >= ? AND observed_at < ? AND invalidated = FALSE
                  GROUP BY FLOOR(TIMESTAMPDIFF(SECOND, ?, observed_at) / ?)
                ) last ON last.at = o.observed_at
                """, rs -> {
            int slot = rs.getInt("slot");
            if (slot >= 0 && slot < count) index[slot] = rs.getDouble("index_value");
        }, start, step, start, end, start, step);

        List<OptionFlowBucket> buckets = new ArrayList<>(count);
        long[] totals = new long[5];
        for (int slot = 0; slot < count; slot += 1) {
            buckets.add(new OptionFlowBucket(from + (long) slot * step, index[slot], callVolume[slot], putVolume[slot],
                    callPremium[slot], putPremium[slot], netPremium[slot]));
            totals[0] += callVolume[slot]; totals[1] += putVolume[slot];
            totals[2] += callPremium[slot]; totals[3] += putPremium[slot]; totals[4] += netPremium[slot];
        }
        OptionFlow flow = new OptionFlow(range, step, from, to, totals[0], totals[1], totals[2], totals[3], totals[4], buckets);
        flowCache.put(key, Map.entry(nowMillis, flow));
        return flow;
    }

    // ------------------------------------------------------------ 지수 선물 차트

    /** 차트 기간과 봉 간격(초). 주식 차트(MarketService.chartBucketSeconds)와 같은 버튼을 쓴다. 지수 기록은 30일만 남는다. */
    private static final Map<String, long[]> FUTURE_CHART_RANGES = Map.of(
            "1h", new long[]{3_600, 60}, "6h", new long[]{21_600, 300}, "12h", new long[]{43_200, 600},
            "1d", new long[]{86_400, 1_800}, "3d", new long[]{259_200, 3_600}, "2w", new long[]{1_209_600, 21_600},
            "1m", new long[]{2_592_000, 86_400});
    private final Map<String, Map.Entry<Long, List<IndexCandle>>> futureChartCache = new ConcurrentHashMap<>();

    /**
     * 지수 관측을 봉으로 묶는다. 선물 가격은 지수와 같으므로 이 봉이 곧 선물 차트다.
     * 봉 번호는 옵션 흐름과 같이 바인딩한 시작 시각과의 차이로 센다.
     * 관측은 3초마다 쌓이므로 짧은 기간은 3초, 하루를 넘는 기간은 30초 동안 같은 결과를 재사용한다.
     */
    public List<IndexCandle> futureChart(String range) {
        long[] spec = FUTURE_CHART_RANGES.get(range);
        if (spec == null) throw new IllegalArgumentException("기간은 1h, 6h, 12h, 1d, 3d, 2w, 1m 중 하나입니다.");
        long nowMillis = clock.millis();
        Map.Entry<Long, List<IndexCandle>> cached = futureChartCache.get(range);
        if (cached != null && nowMillis - cached.getKey() < (spec[0] > 86_400 ? 30_000 : 3_000)) return cached.getValue();

        long step = spec[1];
        long to = (nowMillis / 1000 / step + 1) * step;
        long from = to - ((spec[0] + step - 1) / step) * step;
        Timestamp start = Timestamp.from(Instant.ofEpochSecond(from));
        List<IndexCandle> candles = jdbc.query("""
                SELECT slot,
                       MAX(CASE WHEN first_rank = 1 THEN index_value END) AS open_value,
                       MAX(index_value) AS high_value, MIN(index_value) AS low_value,
                       MAX(CASE WHEN last_rank = 1 THEN index_value END) AS close_value,
                       COUNT(*) AS points
                FROM (
                  SELECT FLOOR(TIMESTAMPDIFF(SECOND, ?, observed_at) / ?) AS slot, index_value,
                         ROW_NUMBER() OVER (PARTITION BY FLOOR(TIMESTAMPDIFF(SECOND, ?, observed_at) / ?) ORDER BY observed_at) AS first_rank,
                         ROW_NUMBER() OVER (PARTITION BY FLOOR(TIMESTAMPDIFF(SECOND, ?, observed_at) / ?) ORDER BY observed_at DESC) AS last_rank
                  FROM derivative_index_observations
                  WHERE observed_at >= ? AND observed_at < ? AND invalidated = FALSE
                ) bucketed
                GROUP BY slot
                ORDER BY slot
                """, (rs, row) -> new IndexCandle(
                Instant.ofEpochSecond(from + rs.getLong("slot") * step).toString(),
                rs.getDouble("open_value"), rs.getDouble("high_value"), rs.getDouble("low_value"),
                rs.getDouble("close_value"), rs.getLong("points")),
                start, step, start, step, start, step, start, Timestamp.from(Instant.ofEpochSecond(to)));
        futureChartCache.put(range, Map.entry(nowMillis, candles));
        return candles;
    }

    // ------------------------------------------------------------ 변동성·시계열

    private double currentVolatility() {
        return indexHistory.volatility(clock.instant());
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
