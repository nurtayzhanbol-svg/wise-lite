package com.wiselite.transfer.lab;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A deliberately tiny "balances" table used to compare concurrency-control strategies in
 * isolation, without the ledger's journal inserts and triggers adding noise.
 */
final class LabSchema {

    private LabSchema() {}

    static void reset(JdbcTemplate jdbc, int accounts, long initialBalance) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS lab_balances (
                    id      INT    PRIMARY KEY,
                    balance BIGINT NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0
                )""");
        jdbc.execute("TRUNCATE lab_balances");
        jdbc.update("INSERT INTO lab_balances (id, balance) SELECT g, ? FROM generate_series(1, ?) g", initialBalance, accounts);
    }

    static long total(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM lab_balances", Long.class);
    }

    static long negativeCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM lab_balances WHERE balance < 0", Long.class);
    }
}
