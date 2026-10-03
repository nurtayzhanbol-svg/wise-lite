package com.wiselite.transfer.ledger;

import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final LedgerRepository repository;

    public AccountService(LedgerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Account openCustomerAccount(UUID ownerId, Currency currency) {
        var account = new Account(UUID.randomUUID(), ownerId, currency, AccountType.CUSTOMER);
        repository.insertAccount(account);
        return account;
    }

    /**
     * Returns the single system account of this type and currency, creating it on first use.
     * Two concurrent first calls race on a unique index; the loser re-reads the winner's row.
     */
    public Account systemAccount(AccountType type, Currency currency) {
        if (type == AccountType.CUSTOMER) {
            throw new IllegalArgumentException("Not a system account type: " + type);
        }
        return repository.findSystemAccount(type, currency).orElseGet(() -> {
            try {
                var account = new Account(UUID.randomUUID(), null, currency, type);
                repository.insertAccount(account);
                return account;
            } catch (DuplicateKeyException e) {
                return repository.findSystemAccount(type, currency).orElseThrow();
            }
        });
    }

    public Account get(UUID id) {
        return repository.findAccount(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    public List<Account> accountsOf(UUID ownerId) {
        return repository.findAccountsByOwner(ownerId);
    }

    public Money balance(UUID accountId) {
        return repository.findBalance(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
