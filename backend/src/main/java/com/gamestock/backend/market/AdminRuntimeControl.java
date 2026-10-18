package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.gamestock.backend.market.MarketModels.TradingRestriction;

@Service
public class AdminRuntimeControl {
    private final JdbcTemplate db;
    public record Settings(boolean botsPaused,boolean marketPaused,Integer cadenceMs,Integer targetPerSymbol) { }
    public AdminRuntimeControl(JdbcTemplate db){this.db=db;}
    public Settings settings(){return db.queryForObject("SELECT * FROM admin_runtime WHERE id=1",(rs,n)->new Settings(rs.getBoolean("bots_paused"),rs.getBoolean("market_paused"),(Integer)rs.getObject("cadence_ms"),(Integer)rs.getObject("target_per_symbol")));}
    public boolean botsPaused(){return settings().botsPaused();}
    public TradingRestriction restriction(String code) {
        if(settings().marketPaused())return new TradingRestriction("ADMIN_HALT","운영 점검","HALT",0,"관리자가 시장을 일시 정지했습니다.","신규 주문과 체결이 중단됩니다. 주문 취소는 가능합니다.",null,null,false,false);
        var rows=db.queryForList("SELECT a.state,a.reason FROM admin_stock_state a JOIN stocks s ON s.id=a.stock_id WHERE s.stock_code=? AND a.state <> 'ACTIVE'",code);
        if(rows.isEmpty())return null;
        var row=rows.get(0);
        return new TradingRestriction("ADMIN_HALT","DELISTED".equals(row.get("state"))?"상장 폐지":"거래 정지","HALT",0,String.valueOf(row.get("reason")),"신규 주문과 체결이 중단됩니다. 주문 취소는 가능합니다.",null,null,false,false);
    }
    void apply(BatchOrderBook book){
        boolean paused=settings().marketPaused();Set<Long> halted=new HashSet<>(db.queryForList("SELECT stock_id FROM admin_stock_state WHERE state<>'ACTIVE'",Long.class));
        for(var stock:book.stocks.values()){stock.adminHalted=paused||halted.contains(stock.id);if(stock.adminHalted)stock.continuous=false;}
    }
    Map<Long,Long> externalCash(){
        Map<Long,Long> result=new HashMap<>();
        db.query("SELECT c.user_id,SUM(c.amount) FROM admin_cash_adjustments c JOIN users u ON u.id=c.user_id LEFT JOIN admin_cash_epochs e ON e.user_id=c.user_id WHERE (e.user_id IS NOT NULL AND c.id>e.last_adjustment_id) OR (e.user_id IS NULL AND (u.account_reset_at IS NULL OR c.created_at>u.account_reset_at)) GROUP BY c.user_id",rs->{result.put(rs.getLong(1),rs.getLong(2));});return result;
    }
    long externalCash(long user){Long result=db.queryForObject("SELECT COALESCE(SUM(c.amount),0) FROM admin_cash_adjustments c JOIN users u ON u.id=c.user_id LEFT JOIN admin_cash_epochs e ON e.user_id=c.user_id WHERE c.user_id=? AND ((e.user_id IS NOT NULL AND c.id>e.last_adjustment_id) OR (e.user_id IS NULL AND (u.account_reset_at IS NULL OR c.created_at>u.account_reset_at)))",Long.class,user);return result==null?0:result;}
    long missionReward(String id,long fallback){var rows=db.queryForList("SELECT enabled,reward_cash FROM admin_mission_config WHERE mission_id=?",id);if(rows.isEmpty())return fallback;var row=rows.get(0);return Boolean.TRUE.equals(row.get("enabled"))||Integer.valueOf(1).equals(row.get("enabled"))?((Number)row.get("reward_cash")).longValue():-1;}
    int minimumWatchlist(String id){var rows=db.queryForList("SELECT minimum_watchlist FROM admin_mission_config WHERE mission_id=?",Integer.class,id);return rows.isEmpty()?("watch".equals(id)?1:0):rows.get(0);}
}
