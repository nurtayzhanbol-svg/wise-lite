package com.wiselite.transfer.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.transfer.IntegrationTest;
import java.util.ArrayList;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression, found by the M8 system test: the first transfers in a fresh database raced to create
 * PAYOUT_CLEARING lazily; the loser's unique violation aborted its transaction and the request got a 500.
 */
@IntegrationTest
class SystemAccountRaceIT {

    @Autowired AccountService accounts;
    @Autowired TransactionTemplate tx;

    @Test
    void concurrentFirstUseInsideTransactionsCreatesOneAccountAndAbortsNobody() throws Exception {
        var currency = Currency.getInstance("ISK"); // not used by other tests, so the account doesn't exist yet
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<UUID>>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return tx.execute(s -> {
                        var account = accounts.systemAccount(AccountType.PAYOUT_CLEARING, currency);
                        // The transaction must still be usable afterwards.
                        accounts.balance(account.id());
                        return account.id();
                    });
                }));
            }
            start.countDown();
        }
        var ids = new java.util.HashSet<UUID>();
        for (var r : results) {
            ids.add(r.get());
        }
        assertThat(ids).hasSize(1);
    }
}
