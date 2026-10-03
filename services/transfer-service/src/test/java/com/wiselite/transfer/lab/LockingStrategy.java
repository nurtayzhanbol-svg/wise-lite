package com.wiselite.transfer.lab;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Four ways to move {@code amount} from one balance to another without overdrawing, plus one
 * broken way. Each returns true on success and false on insufficient funds.
 *
 * <ul>
 *   <li>{@link #NAIVE}: read, check, write, with no locking. Loses updates under concurrency. Never do this.</li>
 *   <li>{@link #PESSIMISTIC}: {@code SELECT ... FOR UPDATE} in sorted id order. This is what {@code LedgerService} uses.</li>
 *   <li>{@link #OPTIMISTIC}: read with a version, {@code UPDATE ... WHERE version = ?}, retry on conflict.</li>
 *   <li>{@link #SERIALIZABLE}: plain reads/writes at SERIALIZABLE isolation, retry on serialization failure (40001).</li>
 *   <li>{@link #ATOMIC_UPDATE}: a single conditional {@code UPDATE ... SET balance = balance - ? WHERE balance >= ?}, no read at all.</li>
 * </ul>
 */
enum LockingStrategy {

    NAIVE {
        @Override
        boolean transfer(Ctx c, int from, int to, long amount) {
            return c.readCommitted.execute(s -> {
                long fromBalance = c.balance(from);
                long toBalance = c.balance(to);
                c.thinkTime();
                if (fromBalance < amount) {
                    return false;
                }
                c.jdbc.update("UPDATE lab_balances SET balance = ? WHERE id = ?", fromBalance - amount, from);
                c.jdbc.update("UPDATE lab_balances SET balance = ? WHERE id = ?", toBalance + amount, to);
                return true;
            });
        }
    },

    PESSIMISTIC {
        @Override
        boolean transfer(Ctx c, int from, int to, long amount) {
            return c.readCommitted.execute(s -> {
                // Lock in a global order (lowest id first) to avoid deadlocks.
                int first = Math.min(from, to);
                int second = Math.max(from, to);
                c.lockBalance(first);
                c.lockBalance(second);
                c.thinkTime();
                long fromBalance = c.balance(from);
                if (fromBalance < amount) {
                    return false;
                }
                c.jdbc.update("UPDATE lab_balances SET balance = balance - ? WHERE id = ?", amount, from);
                c.jdbc.update("UPDATE lab_balances SET balance = balance + ? WHERE id = ?", amount, to);
                return true;
            });
        }
    },

    OPTIMISTIC {
        @Override
        boolean transfer(Ctx c, int from, int to, long amount) {
            return c.withRetries(() -> c.readCommitted.execute(s -> {
                var src = c.jdbc.queryForMap("SELECT balance, version FROM lab_balances WHERE id = ?", from);
                var dst = c.jdbc.queryForMap("SELECT balance, version FROM lab_balances WHERE id = ?", to);
                c.thinkTime();
                long fromBalance = ((Number) src.get("balance")).longValue();
                if (fromBalance < amount) {
                    return false;
                }
                // Compare-and-set on the version. 0 rows updated = someone changed it since our read.
                // UPDATE takes row locks, so even optimistic writers must write in a global order,
                // otherwise A->B and B->A deadlock (each holding one row lock).
                long srcVersion = ((Number) src.get("version")).longValue();
                long dstVersion = ((Number) dst.get("version")).longValue();
                if (from < to) {
                    casUpdate(c, from, -amount, srcVersion);
                    casUpdate(c, to, amount, dstVersion);
                } else {
                    casUpdate(c, to, amount, dstVersion);
                    casUpdate(c, from, -amount, srcVersion);
                }
                return true;
            }));
        }

        private void casUpdate(Ctx c, int id, long delta, long expectedVersion) {
            int rows = c.jdbc.update(
                    "UPDATE lab_balances SET balance = balance + ?, version = version + 1 WHERE id = ? AND version = ?",
                    delta, id, expectedVersion);
            if (rows == 0) {
                throw new OptimisticLockingFailureException("version conflict on " + id);
            }
        }
    },

    SERIALIZABLE {
        @Override
        boolean transfer(Ctx c, int from, int to, long amount) {
            return c.withRetries(() -> c.serializable.execute(s -> {
                long fromBalance = c.balance(from);
                c.balance(to);
                c.thinkTime();
                if (fromBalance < amount) {
                    return false;
                }
                c.updateInOrder(from, to, amount);
                return true;
            }));
        }
    },

    ATOMIC_UPDATE {
        @Override
        boolean transfer(Ctx c, int from, int to, long amount) {
            return c.readCommitted.execute(s -> {
                c.thinkTime();
                // The overdraft guard lives in the WHERE clause. Postgres re-evaluates it on the latest
                // row version after acquiring the row lock, so no read is needed and no update is lost.
                // Rows are still updated in id order to avoid A->B / B->A deadlocks; if the debit runs
                // second and fails its guard, roll back the credit.
                if (to < from) {
                    c.jdbc.update("UPDATE lab_balances SET balance = balance + ? WHERE id = ?", amount, to);
                }
                int rows = c.jdbc.update("UPDATE lab_balances SET balance = balance - ? WHERE id = ? AND balance >= ?",
                        amount, from, amount);
                if (rows == 0) {
                    s.setRollbackOnly();
                    return false;
                }
                if (to > from) {
                    c.jdbc.update("UPDATE lab_balances SET balance = balance + ? WHERE id = ?", amount, to);
                }
                return true;
            });
        }
    };

    abstract boolean transfer(Ctx c, int from, int to, long amount);

    /** Shared plumbing for the strategies. */
    static final class Ctx {
        final JdbcTemplate jdbc;
        final TransactionTemplate readCommitted;
        final TransactionTemplate serializable;
        final long thinkTimeMillis;
        final AtomicLong retries = new AtomicLong();

        Ctx(JdbcTemplate jdbc, PlatformTransactionManager tm, long thinkTimeMillis) {
            this.jdbc = jdbc;
            this.thinkTimeMillis = thinkTimeMillis;
            this.readCommitted = new TransactionTemplate(tm);
            this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            this.serializable = new TransactionTemplate(tm);
            this.serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        }

        long balance(int id) {
            return jdbc.queryForObject("SELECT balance FROM lab_balances WHERE id = ?", Long.class, id);
        }

        void updateInOrder(int from, int to, long amount) {
            for (int id : new int[] {Math.min(from, to), Math.max(from, to)}) {
                jdbc.update("UPDATE lab_balances SET balance = balance + ? WHERE id = ?", id == from ? -amount : amount, id);
            }
        }

        void lockBalance(int id) {
            jdbc.queryForObject("SELECT balance FROM lab_balances WHERE id = ? FOR UPDATE", Long.class, id);
        }

        /** Simulates work done while holding the transaction open (validation, other queries). */
        void thinkTime() {
            if (thinkTimeMillis > 0) {
                try {
                    Thread.sleep(thinkTimeMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }

        /** Retries the whole transaction on version conflicts, serialization failures and deadlocks. */
        boolean withRetries(java.util.function.Supplier<Boolean> attempt) {
            for (int i = 0; ; i++) {
                try {
                    return attempt.get();
                } catch (ConcurrencyFailureException e) {
                    if (i >= 50) {
                        throw e;
                    }
                    retries.incrementAndGet();
                    backoff(i);
                }
            }
        }

        private static void backoff(int attempt) {
            try {
                // Exponential backoff with full jitter, capped: avoids retry storms where the same
                // transactions keep colliding in lockstep.
                long cap = Math.min(50, 1L << Math.min(attempt, 5));
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(cap + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
