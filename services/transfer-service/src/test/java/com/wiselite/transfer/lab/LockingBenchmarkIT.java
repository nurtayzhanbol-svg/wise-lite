package com.wiselite.transfer.lab;

import com.wiselite.transfer.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Compares the locking strategies under two workloads:
 * <ul>
 *   <li><b>hot</b>: every transfer debits account 1, e.g. a popular merchant or a system account.</li>
 *   <li><b>spread</b>: random pairs among 1000 accounts, so collisions are rare.</li>
 * </ul>
 * Run with {@code ./gradlew :services:transfer-service:benchmark}. Writes
 * {@code build/reports/benchmark/locking.md}. The numbers depend on the machine; the shape is what matters.
 */
@Tag("benchmark")
@IntegrationTest
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class LockingBenchmarkIT {

    private static final int THREADS = 32;
    private static final int OPS = 3_000;
    private static final long THINK_MS = 1;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;

    record Result(String workload, LockingStrategy strategy, double opsPerSec, long p50Micros, long p99Micros,
            long retries, long failures, boolean conserved) {}

    @Test
    void benchmark() throws Exception {
        var results = new ArrayList<Result>();
        var strategies = List.of(LockingStrategy.PESSIMISTIC, LockingStrategy.OPTIMISTIC,
                LockingStrategy.SERIALIZABLE, LockingStrategy.ATOMIC_UPDATE);
        for (var workload : List.of("hot", "spread")) {
            for (var strategy : strategies) {
                run(workload, strategy, 300); // warm-up
                results.add(run(workload, strategy, OPS));
            }
        }
        var report = render(results);
        System.out.println(report);
        var out = Path.of("build/reports/benchmark/locking.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    private Result run(String workload, LockingStrategy strategy, int ops) throws Exception {
        int accounts = workload.equals("hot") ? 100 : 1_000;
        LabSchema.reset(jdbc, accounts, 1_000_000_000L);
        long totalBefore = LabSchema.total(jdbc);
        var ctx = new LockingStrategy.Ctx(jdbc, tm, THINK_MS);
        var latencies = new ConcurrentLinkedQueue<Long>();
        var failures = new AtomicLong();

        long start = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < ops; i++) {
                pool.submit(() -> {
                    var rnd = ThreadLocalRandom.current();
                    int from = workload.equals("hot") ? 1 : 1 + rnd.nextInt(accounts);
                    int to;
                    do {
                        to = 1 + rnd.nextInt(accounts);
                    } while (to == from);
                    long t0 = System.nanoTime();
                    try {
                        strategy.transfer(ctx, from, to, 1);
                    } catch (ConcurrencyFailureException e) {
                        failures.incrementAndGet();
                    }
                    latencies.add((System.nanoTime() - t0) / 1_000);
                });
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        return new Result(workload, strategy, ops / seconds, percentile(sorted, 50), percentile(sorted, 99),
                ctx.retries.get(), failures.get(), LabSchema.total(jdbc) == totalBefore && LabSchema.negativeCount(jdbc) == 0);
    }

    private static long percentile(long[] sorted, int p) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)];
    }

    private static String render(List<Result> results) {
        var sb = new StringBuilder("""
                # Locking strategy benchmark

                %d threads, %d transfers per run, %d ms simulated work inside each transaction, %d CPUs.

                | Workload | Strategy | Throughput (ops/s) | p50 (ms) | p99 (ms) | Retries | Gave up | Money conserved |
                |----------|----------|-------------------:|---------:|---------:|--------:|--------:|:---------------:|
                """.formatted(THREADS, OPS, THINK_MS, Runtime.getRuntime().availableProcessors()));
        for (var r : results) {
            sb.append("| %s | %s | %.0f | %.1f | %.1f | %d | %d | %s |%n".formatted(r.workload(), r.strategy(), r.opsPerSec(),
                    r.p50Micros() / 1000.0, r.p99Micros() / 1000.0, r.retries(), r.failures(), r.conserved() ? "yes" : "**NO**"));
        }
        return sb.toString();
    }
}
