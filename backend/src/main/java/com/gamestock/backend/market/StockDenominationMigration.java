package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** One atomic unit conversion. Cash-in-lieu is an issuer settlement, never a fabricated market fill. */
final class StockDenominationMigration {
    private final JdbcTemplate db;
    StockDenominationMigration(JdbcTemplate db) { this.db=db; }

    static void ensureTradePriceScale(JdbcTemplate db) {
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trades' AND column_name='price_scale'",Integer.class)==0)
            db.execute("ALTER TABLE trades ADD COLUMN price_scale DOUBLE NOT NULL DEFAULT 1");
        ensureDenominationLedger(db);
    }

    private static void ensureDenominationLedger(JdbcTemplate db) {
        db.execute("""
                CREATE TABLE IF NOT EXISTS stock_denominations (
                  stock_id BIGINT PRIMARY KEY,stock_code VARCHAR(12) NOT NULL,
                  old_mark BIGINT NOT NULL,new_mark BIGINT NOT NULL,old_price BIGINT NOT NULL,new_price BIGINT NOT NULL,
                  last_trade_id BIGINT NOT NULL,applied_at TIMESTAMP(3) NOT NULL)
                """);
    }

    static void ensureTables(JdbcTemplate db) {
        // Existing rows get version 0; inserts after the schema upgrade get version 1.
        // The two DDL statements can be retried after a crash without marking old rows done.
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='stocks' AND column_name='denomination_version'",Integer.class)==0)
            db.execute("ALTER TABLE stocks ADD COLUMN denomination_version INT NOT NULL DEFAULT 0");
        db.execute("ALTER TABLE stocks ALTER COLUMN denomination_version SET DEFAULT 1");
        ensureTradePriceScale(db);
        db.execute("""
                CREATE TABLE IF NOT EXISTS stock_denomination_positions (
                  stock_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
                  old_quantity INT NOT NULL,new_quantity INT NOT NULL,old_average BIGINT NOT NULL,new_average BIGINT NOT NULL,
                  old_realized BIGINT NOT NULL,new_realized BIGINT NOT NULL,cash_paid BIGINT NOT NULL,
                  applied_at TIMESTAMP(3) NOT NULL,PRIMARY KEY(stock_id,user_id))
                """);
        db.execute("""
                CREATE TABLE IF NOT EXISTS stock_denomination_orders (
                  order_id BIGINT PRIMARY KEY,stock_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
                  reserved_cash BIGINT NOT NULL,reserved_quantity INT NOT NULL,remaining_quantity INT NOT NULL,
                  applied_at TIMESTAMP(3) NOT NULL)
                """);
        // Projection volumes can contain a fraction of a post-consolidation share.
        // Preserve notional exactly so old VWAPs do not change after a restart/rebuild.
        for(String table:List.of("market_trade_buckets","bot_trade_seconds","bot_trade_minutes","bot_trade_hours")) {
            Integer scale=db.queryForObject("SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='quantity'",Integer.class,table);
            if(scale==null || scale<18)db.execute("ALTER TABLE "+table+" MODIFY COLUMN quantity DECIMAL(38,18) NOT NULL");
        }
    }

    record Conversion(int quantity,long average,long cash,long realized) { }

    static Conversion convert(int quantity,long average,long realized,long oldMark,long newMark) {
        if(quantity<0 || average<0 || oldMark<=0 || newMark<=0)throw new IllegalArgumentException("Invalid denomination input");
        long oldValue=Math.multiplyExact((long)quantity,oldMark);
        int next=Math.toIntExact(oldValue/newMark);
        long cash=Math.subtractExact(oldValue,Math.multiplyExact((long)next,newMark));
        long nextAverage=next==0?0:Math.max(1,BigDecimal.valueOf(average).multiply(BigDecimal.valueOf(newMark))
                .divide(BigDecimal.valueOf(oldMark),0,RoundingMode.HALF_UP).longValueExact());
        // A whole-won average may leave a basis remainder. Book it with the cash-in-lieu
        // realization so realized + unrealized P/L remains exact, including a full cash-out.
        long delta=Math.subtractExact(Math.addExact(cash,Math.multiplyExact((long)next,nextAverage)),
                Math.multiplyExact((long)quantity,average));
        return new Conversion(next,nextAverage,cash,Math.addExact(realized,delta));
    }

    private record Stock(long id,String code,long price,long mark) { }
    private record Account(long id,long asset,long profit) { }

    void apply(Map<String,Long> targets,Runnable settlePending,Instant now) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        tx.executeWithoutResult(status->{
            db.queryForObject("SELECT id FROM market_locks WHERE id=1 FOR UPDATE",Integer.class);
            List<Stock> pending=db.query("""
                    SELECT s.id,s.stock_code,s.current_price,COALESCE(m.mark_price,s.current_price) AS mark
                    FROM stocks s LEFT JOIN market_price_metrics m ON m.stock_id=s.id
                    WHERE s.denomination_version=0 ORDER BY s.id FOR UPDATE
                    """,(rs,n)->new Stock(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getLong(4)))
                    .stream().filter(s->targets.containsKey(s.code())).toList();
            if(pending.isEmpty())return;
            // Both exchange paths use this order: market gate, runtime, basket, accounts.
            db.queryForObject("SELECT id FROM admin_runtime WHERE id=1 FOR UPDATE",Integer.class);
            var baskets=new DerivativeBaskets(db);baskets.lock();
            db.queryForList("SELECT id FROM users ORDER BY id FOR UPDATE",Long.class);
            settlePending.run();
            if(db.queryForObject("SELECT COUNT(*) FROM settlements WHERE status='PENDING'",Integer.class)>0)
                throw new IllegalStateException("환산 전에 미결제 거래를 정산해야 합니다.");
            var accountsBefore=accounts();
            var basketsBefore=baskets.loadCurrent();
            for(Stock stock:pending)convertStock(stock,targets.get(stock.code()),now);
            var basketsAfter=baskets.loadCurrent();
            for(var entry:basketsBefore.baskets().entrySet()) {
                var after=basketsAfter.basket(entry.getKey());
                if(Math.abs(entry.getValue().price()-after.price())>1e-7*Math.max(1,entry.getValue().price()))
                    throw new IllegalStateException("환산 중 ETF 가치가 변경되었습니다.");
                double delta=entry.getValue().previous()-after.previous();
                db.update("UPDATE derivative_baskets SET previous_adjustment=?,reference_day=COALESCE((SELECT trading_date FROM market_protection_state WHERE id=1),CURRENT_DATE) WHERE symbol=?",after.previousAdjustment()+delta,entry.getKey());
            }
            if(!accountsBefore.equals(accounts()))throw new IllegalStateException("환산 중 계좌 자산 또는 총손익이 변경되었습니다.");
            baskets.advanceValuationVersion();
        });
    }

    private List<Account> accounts() {
        return db.query("""
                SELECT u.id,u.cash+COALESCE(p.value,0)+COALESCE(o.reserved,0) AS asset,COALESCE(p.profit,0) AS profit
                FROM users u LEFT JOIN (
                  SELECT p.user_id,SUM(p.quantity*COALESCE(m.mark_price,s.current_price)) AS value,
                    SUM(p.realized_profit_loss+p.quantity*(COALESCE(m.mark_price,s.current_price)-p.average_price)) AS profit
                  FROM portfolios p JOIN stocks s ON s.id=p.stock_id LEFT JOIN market_price_metrics m ON m.stock_id=s.id GROUP BY p.user_id
                ) p ON p.user_id=u.id LEFT JOIN (
                  SELECT user_id,SUM(reserved_cash) AS reserved FROM orders WHERE status IN ('OPEN','PARTIAL') GROUP BY user_id
                ) o ON o.user_id=u.id ORDER BY u.id
                """,(rs,n)->new Account(rs.getLong(1),rs.getBigDecimal(2).longValueExact(),rs.getBigDecimal(3).longValueExact()));
    }

    private void convertStock(Stock stock,long target,Instant now) {
        if(stock.mark()<=0 || target<=0 || target%PriceLimitPolicy.tickSize(target)!=0)
            throw new IllegalStateException("Invalid denomination reference: "+stock.code());
        long id=stock.id();double factor=target/(double)stock.mark();
        long newPrice=tickPrice(stock.price()*factor);
        Timestamp at=Timestamp.from(now);
        long cutoff=db.queryForObject("SELECT COALESCE(MAX(id),0) FROM trades WHERE stock_id=?",Long.class,id);
        db.update("INSERT INTO stock_denominations VALUES(?,?,?,?,?,?,?,?)",id,stock.code(),stock.mark(),target,stock.price(),newPrice,cutoff,at);
        db.update("""
                INSERT INTO stock_denomination_orders
                SELECT id,stock_id,user_id,reserved_cash,reserved_quantity,remaining_quantity,?
                FROM orders WHERE stock_id=? AND status IN ('OPEN','PARTIAL')
                """,at,id);
        db.update("UPDATE users u JOIN (SELECT user_id,SUM(reserved_cash) AS refund FROM stock_denomination_orders WHERE stock_id=? GROUP BY user_id) r ON r.user_id=u.id SET u.cash=u.cash+r.refund",id);
        db.update("UPDATE orders SET status='CANCELLED',remaining_quantity=0,reserved_cash=0,reserved_quantity=0 WHERE stock_id=? AND status IN ('OPEN','PARTIAL')",id);

        List<Object[]> audit=new ArrayList<>(),positions=new ArrayList<>();
        db.query("SELECT user_id,quantity,settled_quantity,average_price,realized_profit_loss FROM portfolios WHERE stock_id=? ORDER BY user_id FOR UPDATE",rs->{
            int quantity=rs.getInt(2);
            if(quantity!=rs.getInt(3))throw new IllegalStateException("Unsettled position during denomination");
            var converted=convert(quantity,rs.getLong(4),rs.getLong(5),stock.mark(),target);
            audit.add(new Object[]{id,rs.getLong(1),quantity,converted.quantity(),rs.getLong(4),converted.average(),rs.getLong(5),converted.realized(),converted.cash(),at});
            positions.add(new Object[]{converted.quantity(),converted.quantity(),converted.average(),converted.realized(),id,rs.getLong(1)});
        },id);
        if(!audit.isEmpty()) {
            db.batchUpdate("INSERT INTO stock_denomination_positions VALUES(?,?,?,?,?,?,?,?,?,?)",audit);
            db.batchUpdate("UPDATE portfolios SET quantity=?,settled_quantity=?,average_price=?,realized_profit_loss=? WHERE stock_id=? AND user_id=?",positions);
            db.update("UPDATE users u JOIN stock_denomination_positions p ON p.user_id=u.id SET u.cash=u.cash+p.cash_paid WHERE p.stock_id=?",id);
            for(String table:List.of("lp_cash_projection","lp_risk_books")) {
                String column=table.equals("lp_risk_books")?"opening_cash":"cash";
                db.update("UPDATE "+table+" r JOIN (SELECT p.stock_id,SUM(p.cash_paid) AS cash_paid FROM stock_denomination_positions p JOIN users u ON u.id=p.user_id WHERE p.stock_id=? AND u.password_hash='BOT' GROUP BY p.stock_id) a ON a.stock_id=r.stock_id SET r."+column+"=r."+column+"+a.cash_paid",id);
            }
        }
        db.update("UPDATE stocks SET current_price=?,previous_price=GREATEST(1,ROUND(previous_price*?)),total_volume=ROUND(total_volume/?),denomination_version=1 WHERE id=?",newPrice,factor,factor,id);
        db.update("UPDATE market_price_metrics SET mark_price=?,hidden_fundamental=GREATEST(1,hidden_fundamental*?),anchor_price=GREATEST(1,ROUND(anchor_price*?)) WHERE stock_id=?",target,factor,factor,id);
        db.update("UPDATE market_fundamental_state SET log_value=log_value+LOG(?) WHERE stock_id=?",factor,id);
        db.update("UPDATE market_consensus_state SET value=GREATEST(1,value*?) WHERE stock_id=?",factor,id);
        db.update("UPDATE stock_protection_state SET day_reference=GREATEST(1,ROUND(day_reference*?)),static_reference=GREATEST(1,ROUND(static_reference*?)),dynamic_reference=GREATEST(1,ROUND(dynamic_reference*?)),last_trade_price=GREATEST(1,ROUND(last_trade_price*?)) WHERE stock_id=?",factor,factor,factor,factor,id);
        db.update("UPDATE price_alerts SET target_price=GREATEST(1,ROUND(target_price*?)) WHERE stock_id=?",factor,id);
        db.update("UPDATE market_events SET price_at_publish=GREATEST(1,ROUND(price_at_publish*?)) WHERE stock_id=? AND price_at_publish IS NOT NULL",factor,id);
        db.update("UPDATE stock_price_history SET price=GREATEST(1,ROUND(price*?)) WHERE stock_id=?",factor,id);
        db.update("UPDATE daily_market_summaries SET open_price=GREATEST(1,ROUND(open_price*?)),close_price=GREATEST(1,ROUND(close_price*?)),total_volume=ROUND(total_volume/?) WHERE stock_id=?",factor,factor,factor,id);
        db.update("UPDATE trades SET price_scale=price_scale*? WHERE stock_id=? AND id<=?",factor,id,cutoff);
        // Rebuild relational projections from the adjusted-price view of the original fills.
        db.update("DELETE FROM market_trade_buckets WHERE stock_id=?",id);
        db.update("UPDATE market_metric_cursors SET last_id=0 WHERE stock_id=?",id);
        for(String table:List.of("bot_trade_seconds","bot_trade_minutes","bot_trade_hours"))
            db.update("UPDATE "+table+" SET open_price=GREATEST(1,ROUND(open_price*?)),high_price=GREATEST(1,ROUND(high_price*?)),low_price=GREATEST(1,ROUND(low_price*?)),close_price=GREATEST(1,ROUND(close_price*?)),quantity=quantity/? WHERE stock_id=?",factor,factor,factor,factor,factor,id);
        db.update("DELETE FROM bot_trade_tape WHERE stock_id=?",id);
        db.update("UPDATE bot_ledger_totals SET quantity=ROUND(quantity/?) WHERE stock_id=?",factor,id);
        db.update("UPDATE market_inventory_issuance SET quantity=ROUND(quantity/?) WHERE stock_id=?",factor,id);
        db.update("UPDATE lp_risk_books SET target_inventory=GREATEST(1,ROUND(target_inventory/?)),max_inventory=GREATEST(1,ROUND(max_inventory/?)) WHERE stock_id=?",factor,factor,id);
        db.update("UPDATE derivative_reference_prices SET price=price*?,revision=revision+1 WHERE stock_id=?",factor,id);
        db.update("UPDATE derivative_index_bases SET reference_price=GREATEST(1,ROUND(reference_price*?)) WHERE stock_id=?",factor,id);
        db.update("UPDATE derivative_basket_members SET units=units/?,base_price=GREATEST(1,ROUND(base_price*?)),last_price=last_price*? WHERE stock_id=?",factor,factor,factor,id);
        db.update("UPDATE market_book_revisions SET revision=revision+1 WHERE stock_id=?",id);
    }

    private static long tickPrice(double price) {
        long tick=PriceLimitPolicy.tickSize(Math.max(1,Math.round(price)));
        return PriceLimitPolicy.floorToTick(Math.max(1,Math.round(price/tick)*tick));
    }
}
