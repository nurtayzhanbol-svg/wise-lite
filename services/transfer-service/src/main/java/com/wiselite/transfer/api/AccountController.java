package com.wiselite.transfer.api;

import com.wiselite.transfer.ledger.Account;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    public record OpenAccountRequest(UUID ownerId, String currency) {}

    public record MoneyView(BigDecimal amount, String currency) {
        static MoneyView of(Money money) {
            return new MoneyView(money.toDecimal(), money.currency().getCurrencyCode());
        }
    }

    public record AccountView(UUID id, UUID ownerId, String currency, String type, MoneyView balance) {}

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountView> open(@RequestBody OpenAccountRequest request) {
        if (request.ownerId() == null || request.currency() == null) {
            throw new IllegalArgumentException("ownerId and currency are required");
        }
        var account = accounts.openCustomerAccount(request.ownerId(), Currency.getInstance(request.currency()));
        return ResponseEntity.created(URI.create("/accounts/" + account.id())).body(view(account));
    }

    @GetMapping("/accounts/{id}")
    public AccountView get(@PathVariable UUID id) {
        return view(accounts.get(id));
    }

    @GetMapping("/owners/{ownerId}/accounts")
    public List<AccountView> ofOwner(@PathVariable UUID ownerId) {
        return accounts.accountsOf(ownerId).stream().map(this::view).toList();
    }

    private AccountView view(Account account) {
        return new AccountView(account.id(), account.ownerId(), account.currency().getCurrencyCode(),
                account.type().name(), MoneyView.of(accounts.balance(account.id())));
    }
}
