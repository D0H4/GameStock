package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

/** Idempotent operations tables; called by the existing startup migration. */
final class AdminSchema {
    private AdminSchema() { }
    static void ensure(JdbcTemplate db) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_permissions (
              user_id BIGINT NOT NULL, permission VARCHAR(24) NOT NULL,
              PRIMARY KEY(user_id, permission), FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_audit (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, admin_id BIGINT NOT NULL,
              action VARCHAR(40) NOT NULL, target VARCHAR(128) NOT NULL, reason VARCHAR(300) NOT NULL,
              before_json MEDIUMTEXT NOT NULL, after_json MEDIUMTEXT NOT NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              INDEX ix_admin_audit_actor(admin_id,id), INDEX ix_admin_audit_action(action,id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_runtime (
              id INT PRIMARY KEY, bots_paused BOOLEAN NOT NULL DEFAULT FALSE,
              market_paused BOOLEAN NOT NULL DEFAULT FALSE,
              cadence_ms INT NULL, target_per_symbol INT NULL)
            """);
        db.update("INSERT IGNORE INTO admin_runtime(id) VALUES(1)");
        addRuntimeColumn(db,"traders_paused","BOOLEAN NOT NULL DEFAULT FALSE AFTER bots_paused");
        addRuntimeColumn(db,"trader_ramp_seconds","INT NULL");
        addRuntimeColumn(db,"traders_resumed_at","TIMESTAMP(3) NULL");
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_stock_state (
              stock_id BIGINT PRIMARY KEY, state VARCHAR(16) NOT NULL,
              reason VARCHAR(300) NOT NULL, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              FOREIGN KEY(stock_id) REFERENCES stocks(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_stock_metadata (
              stock_id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL,
              developer VARCHAR(100) NOT NULL, genre VARCHAR(50) NOT NULL,
              FOREIGN KEY(stock_id) REFERENCES stocks(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_cash_adjustments (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, request_key VARCHAR(80) NOT NULL UNIQUE,
              admin_id BIGINT NOT NULL, user_id BIGINT NOT NULL, amount BIGINT NOT NULL,
              reason VARCHAR(300) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_cash_epochs (
              user_id BIGINT PRIMARY KEY, last_adjustment_id BIGINT NOT NULL,
              FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_content (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, kind VARCHAR(16) NOT NULL,
              title VARCHAR(150) NOT NULL, body TEXT NOT NULL, stock_id BIGINT NULL,
              published BOOLEAN NOT NULL DEFAULT FALSE, starts_at TIMESTAMP(3) NULL, ends_at TIMESTAMP(3) NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              FOREIGN KEY(stock_id) REFERENCES stocks(id), INDEX ix_admin_content_public(published,kind,id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_mission_config (
              mission_id VARCHAR(40) PRIMARY KEY, enabled BOOLEAN NOT NULL,
              reward_cash BIGINT NOT NULL, minimum_watchlist INT NOT NULL DEFAULT 0)
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS user_reports (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, reporter_id BIGINT NOT NULL,
              target_user_id BIGINT NOT NULL, detail VARCHAR(1000) NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'OPEN', resolution VARCHAR(1000) NULL,
              resolved_by BIGINT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              FOREIGN KEY(reporter_id) REFERENCES users(id), FOREIGN KEY(target_user_id) REFERENCES users(id),
              INDEX ix_reports_status(status,id), INDEX ix_reports_reporter(reporter_id,id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_seasons (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
              started_at TIMESTAMP(3) NULL, ended_at TIMESTAMP(3) NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_season_entries (
              season_id BIGINT NOT NULL, user_id BIGINT NOT NULL, nickname VARCHAR(50) NOT NULL,
              opening_asset BIGINT NOT NULL, closing_asset BIGINT NULL, rank_no INT NULL,
              reward_cash BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(season_id,user_id),
              FOREIGN KEY(season_id) REFERENCES admin_seasons(id), FOREIGN KEY(user_id) REFERENCES users(id))
            """);
    }
    private static void addRuntimeColumn(JdbcTemplate db,String column,String definition){
        Integer count=db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='admin_runtime' AND column_name=?",Integer.class,column);
        if(count!=null&&count==0)db.execute("ALTER TABLE admin_runtime ADD COLUMN "+column+" "+definition);
    }
    static void restoreStockMetadata(JdbcTemplate db){
        db.update("UPDATE games g JOIN stocks s ON s.game_id=g.id JOIN admin_stock_metadata a ON a.stock_id=s.id SET g.name=a.name,g.developer=a.developer,g.genre=a.genre");
    }
}
