package com.wiselite.transfer.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.transfer.IntegrationTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reproduces the classic anomalies on purpose, then shows each fix holding. If a "broken"
 * test here stops failing in the expected way, the demonstration (not the product) is wrong.
 */
@IntegrationTest
class ConcurrencyAnomaliesIT {

    private static final int THREADS = 8;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;

    @BeforeEach
    void reset() {
        LabSchema.reset(jdbc, 2, 1_000);
    }

    @Test
    void naiveReadModifyWriteLosesUpdatesAndCreatesMoney() throws Exception {
        var ctx = new LockingStrategy.Ctx(jdbc, tm, 5);

        run(THREADS, 10, () -> LockingStrategy.NAIVE.transfer(ctx, 1, 2, 1));

        // 80 transfers of 1 "succeeded", but some writes overwrote others: account 2 received
        // less than 80. (Total happens to stay 2000 here because both sides lose symmetrically;
        // the ledger's SUM(entries) check would catch the projection drift.)
        long received = jdbc.queryForObject("SELECT balance FROM lab_balances WHERE id = 2", Long.class) - 1_000;
        assertThat(received).isLessThan(80);
    }

    @Test
    void naiveCheckThenActOverdraws() throws Exception {
        LabSchema.reset(jdbc, 2, 10);
        var ctx = new LockingStrategy.Ctx(jdbc, tm, 20);
        var results = run(THREADS, 1, () -> LockingStrategy.NAIVE.transfer(ctx, 1, 2, 10));

        // Everyone read balance=10 before anyone wrote, so more than one "succeeded" spending the same 10.
        assertThat(results.stream().filter(b -> b).count()).isGreaterThan(1);
    }

    @Test
    void everyCorrectStrategyConservesMoneyAndNeverOverdraws() throws Exception {
        for (var strategy : List.of(LockingStrategy.PESSIMISTIC, LockingStrategy.OPTIMISTIC,
                LockingStrategy.SERIALIZABLE, LockingStrategy.ATOMIC_UPDATE)) {
            LabSchema.reset(jdbc, 2, 50);
            var ctx = new LockingStrategy.Ctx(jdbc, tm, 1);

            // 8 threads x 10 debits of 1 from account 1 (balance 50), plus opposing traffic 2 -> 1.
            var results = run(THREADS, 10, () -> {
                boolean forward = Thread.currentThread().threadId() % 2 == 0;
                return forward ? strategy.transfer(ctx, 1, 2, 1) : strategy.transfer(ctx, 2, 1, 1);
            });

            assertThat(results).as(strategy.name()).hasSize(THREADS * 10);
            assertThat(LabSchema.total(jdbc)).as(strategy.name()).isEqualTo(100);
            assertThat(LabSchema.negativeCount(jdbc)).as(strategy.name()).isZero();
        }
    }

    @Test
    void lockingInRequestOrderDeadlocksAndPostgresAbortsOneTransaction() throws Exception {
        var barrier = new CyclicBarrier(2);
        var tx = new TransactionTemplate(tm);
        Callable<Void> aToB = () -> lockBoth(tx, barrier, 1, 2);
        Callable<Void> bToA = () -> lockBoth(tx, barrier, 2, 1);

        var outcomes = new ArrayList<Throwable>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(pool.submit(aToB), pool.submit(bToA));
            for (var f : futures) {
                try {
                    f.get();
                    outcomes.add(null);
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
        }

        // Exactly one victim, with SQLSTATE 40P01 (deadlock_detected). The other transaction commits.
        assertThat(outcomes.stream().filter(t -> t != null)).hasSize(1);
        var victim = outcomes.stream().filter(t -> t != null).findFirst().orElseThrow();
        assertThat(sqlState(victim)).isEqualTo("40P01");
    }

    private Void lockBoth(TransactionTemplate tx, CyclicBarrier barrier, int first, int second) {
        tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT balance FROM lab_balances WHERE id = ? FOR UPDATE", Long.class, first);
            try {
                barrier.await(); // both threads now hold their first lock
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            jdbc.queryForObject("SELECT balance FROM lab_balances WHERE id = ? FOR UPDATE", Long.class, second);
        });
        return null;
    }

    private static String sqlState(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static List<Boolean> run(int threads, int perThread, Callable<Boolean> op) throws Exception {
        var results = new ArrayList<Boolean>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < threads * perThread; i++) {
                futures.add(pool.submit(op));
            }
            for (var f : futures) {
                results.add(f.get());
            }
        }
        return results;
    }
}
