package com.wiselite.transfer.ledger;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way money moves. {@link #post} is atomic: either every posting of the journal
 * entry is applied and balances are updated, or nothing is.
 */
@Service
public class LedgerService {

    private final LedgerRepository repository;

    public LedgerService(LedgerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void post(JournalEntry entry) {
        // Lock every touched balance in a fixed global order. Two transfers A->B and B->A
        // would otherwise each hold one lock and wait for the other forever (deadlock).
        Map<UUID, LedgerRepository.LockedBalance> locked = new HashMap<>();
        entry.postings().stream()
                .map(Posting::accountId)
                .sorted(Comparator.naturalOrder())
                .forEach(id -> locked.put(id, repository.lockBalance(id).orElseThrow(() -> new AccountNotFoundException(id))));

        Map<UUID, Long> newBalances = new HashMap<>();
        for (var posting : entry.postings()) {
            var balance = locked.get(posting.accountId());
            if (!balance.currency().equals(posting.amount().currency())) {
                throw new CurrencyMismatchException(balance.currency(), posting.amount().currency());
            }
            long newBalance = Math.addExact(balance.balanceMinor(), posting.amount().minor());
            if (newBalance < 0 && !balance.allowNegative()) {
                throw new InsufficientFundsException(
                        posting.accountId(),
                        new Money(balance.balanceMinor(), balance.currency()),
                        posting.amount().negate());
            }
            newBalances.put(posting.accountId(), newBalance);
        }

        repository.insertJournalEntry(entry);
        newBalances.forEach(repository::updateBalance);
    }
}
