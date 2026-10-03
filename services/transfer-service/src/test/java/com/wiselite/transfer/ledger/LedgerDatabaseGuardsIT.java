package com.wiselite.transfer.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wiselite.transfer.IntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The application validates everything already. These tests bypass it with raw SQL to prove
 * the database rejects corrupt data on its own (defence in depth).
 */
@IntegrationTest
class LedgerDatabaseGuardsIT {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    AccountService accounts;

    @Autowired
    LedgerService ledger;

    @Test
    void unbalancedJournalEntryFailsAtCommit() {
        var a = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var b = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var journal = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO journal_entries (id, type, reference) VALUES (?, 'ADJUSTMENT', 'raw')", journal);
            jdbc.update("INSERT INTO ledger_entries (journal_entry_id, account_id, currency, amount_minor) VALUES (?, ?, 'EUR', 100)", journal, a.id());
            jdbc.update("INSERT INTO ledger_entries (journal_entry_id, account_id, currency, amount_minor) VALUES (?, ?, 'EUR', -99)", journal, b.id());
        })).rootCause().hasMessageContaining("is unbalanced");
    }

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        // Row-level triggers only fire for existing rows, so create one first.
        var a = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, EUR);
        var entry = JournalEntry.of(JournalEntryType.TOP_UP, "guard-" + a.id(),
                Posting.debit(funding.id(), Money.of("5.00", "EUR")),
                Posting.credit(a.id(), Money.of("5.00", "EUR")));
        ledger.post(entry);

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_minor = amount_minor * 2 WHERE journal_entry_id = ?", entry.id()))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entries WHERE id = ?", entry.id()))
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void customerBalanceCannotBeNegativeEvenViaRawSql() {
        var a = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        assertThatThrownBy(() -> jdbc.update("UPDATE account_balances SET balance_minor = -1 WHERE account_id = ?", a.id()))
                .rootCause().hasMessageContaining("balance_not_negative");
    }

    @Test
    void entryCurrencyMustMatchAccountCurrency() {
        var a = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var journal = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO journal_entries (id, type, reference) VALUES (?, 'ADJUSTMENT', 'raw')", journal);
            jdbc.update("INSERT INTO ledger_entries (journal_entry_id, account_id, currency, amount_minor) VALUES (?, ?, 'USD', 100)", journal, a.id());
        })).rootCause().hasMessageContaining("entries_account_fk");
    }
}
