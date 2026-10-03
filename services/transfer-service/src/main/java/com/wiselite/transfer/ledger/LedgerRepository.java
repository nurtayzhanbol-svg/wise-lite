package com.wiselite.transfer.ledger;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL on purpose: locking and constraint behaviour should be visible, not hidden by an ORM. */
@Repository
public class LedgerRepository {

    /** Balance row as read under a row lock. */
    public record LockedBalance(UUID accountId, Currency currency, long balanceMinor, boolean allowNegative) {}

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the system account unless one exists for (type, currency); safe under concurrency. */
    public void insertSystemAccountIfAbsent(Account account) {
        int inserted = jdbc.sql("""
                        INSERT INTO accounts (id, owner_id, currency, type) VALUES (?, NULL, ?, ?)
                        ON CONFLICT (type, currency) WHERE owner_id IS NULL DO NOTHING""")
                .params(account.id(), account.currency().getCurrencyCode(), account.type().name())
                .update();
        if (inserted == 1) {
            jdbc.sql("INSERT INTO account_balances (account_id, currency, allow_negative) VALUES (?, ?, ?)")
                    .params(account.id(), account.currency().getCurrencyCode(), account.type().allowsNegativeBalance())
                    .update();
        }
    }

    public void insertAccount(Account account) {
        jdbc.sql("INSERT INTO accounts (id, owner_id, currency, type) VALUES (?, ?, ?, ?)")
                .params(account.id(), account.ownerId(), account.currency().getCurrencyCode(), account.type().name())
                .update();
        jdbc.sql("INSERT INTO account_balances (account_id, currency, allow_negative) VALUES (?, ?, ?)")
                .params(account.id(), account.currency().getCurrencyCode(), account.type().allowsNegativeBalance())
                .update();
    }

    public Optional<Account> findAccount(UUID id) {
        return jdbc.sql("SELECT id, owner_id, currency, type FROM accounts WHERE id = ?")
                .param(id)
                .query(LedgerRepository::mapAccount)
                .optional();
    }

    public List<Account> findAccountsByOwner(UUID ownerId) {
        return jdbc.sql("SELECT id, owner_id, currency, type FROM accounts WHERE owner_id = ? ORDER BY currency")
                .param(ownerId)
                .query(LedgerRepository::mapAccount)
                .list();
    }

    public Optional<Account> findSystemAccount(AccountType type, Currency currency) {
        return jdbc.sql("SELECT id, owner_id, currency, type FROM accounts WHERE owner_id IS NULL AND type = ? AND currency = ?")
                .params(type.name(), currency.getCurrencyCode())
                .query(LedgerRepository::mapAccount)
                .optional();
    }

    /** {@code SELECT ... FOR UPDATE}: blocks other writers of this balance until our transaction ends. */
    public Optional<LockedBalance> lockBalance(UUID accountId) {
        return jdbc.sql("SELECT account_id, currency, balance_minor, allow_negative FROM account_balances WHERE account_id = ? FOR UPDATE")
                .param(accountId)
                .query((rs, n) -> new LockedBalance(
                        rs.getObject("account_id", UUID.class),
                        Currency.getInstance(rs.getString("currency")),
                        rs.getLong("balance_minor"),
                        rs.getBoolean("allow_negative")))
                .optional();
    }

    public Optional<Money> findBalance(UUID accountId) {
        return jdbc.sql("SELECT currency, balance_minor FROM account_balances WHERE account_id = ?")
                .param(accountId)
                .query((rs, n) -> new Money(rs.getLong("balance_minor"), Currency.getInstance(rs.getString("currency"))))
                .optional();
    }

    public void insertJournalEntry(JournalEntry entry) {
        jdbc.sql("INSERT INTO journal_entries (id, type, reference) VALUES (?, ?, ?)")
                .params(entry.id(), entry.type().name(), entry.reference())
                .update();
        for (var p : entry.postings()) {
            jdbc.sql("INSERT INTO ledger_entries (journal_entry_id, account_id, currency, amount_minor) VALUES (?, ?, ?, ?)")
                    .params(entry.id(), p.accountId(), p.amount().currency().getCurrencyCode(), p.amount().minor())
                    .update();
        }
    }

    public void updateBalance(UUID accountId, long newBalanceMinor) {
        jdbc.sql("UPDATE account_balances SET balance_minor = ?, updated_at = now() WHERE account_id = ?")
                .params(newBalanceMinor, accountId)
                .update();
    }

    /** Balance recomputed from the source of truth; used to verify the projection. */
    public long sumEntries(UUID accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE account_id = ?")
                .param(accountId)
                .query(Long.class)
                .single();
    }

    public long countJournalEntries(String reference) {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE reference = ?")
                .param(reference)
                .query(Long.class)
                .single();
    }

    private static Account mapAccount(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                Currency.getInstance(rs.getString("currency")),
                AccountType.valueOf(rs.getString("type")));
    }
}
