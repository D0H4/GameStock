package com.gamestock.backend.openapi;

import org.springframework.jdbc.core.JdbcTemplate;

/** Called under the existing startup migration lock. No runtime DDL in a trade transaction. */
public final class OpenApiSchema {
    private OpenApiSchema() { }

    public static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS open_api_keys (
                  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  name VARCHAR(80) NOT NULL,
                  key_prefix VARCHAR(16) NOT NULL,
                  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
                  permission VARCHAR(8) NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL,
                  expires_at TIMESTAMP(3) NOT NULL,
                  revoked_at TIMESTAMP(3) NULL,
                  INDEX ix_open_api_keys_user (user_id, created_at),
                  CONSTRAINT fk_open_api_key_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        // No users FK: admission must not acquire a user lock before the stock locks
        // taken by the matching engine. The owner always comes from a verified key.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS open_api_requests (
                  user_id BIGINT NOT NULL,
                  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                  payload MEDIUMTEXT NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (user_id, request_id),
                  INDEX ix_open_api_requests_created (created_at)
                )
                """);
    }
}
