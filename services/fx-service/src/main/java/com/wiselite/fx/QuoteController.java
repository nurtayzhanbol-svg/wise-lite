package com.wiselite.fx;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuoteController {

    public record CreateQuoteRequest(String sourceCurrency, String targetCurrency, BigDecimal sourceAmount) {}

    public record ConsumeRequest(String consumerReference) {}

    /** Everything a customer needs to see before committing: no hidden markup in the rate. */
    public record QuoteView(UUID id, String sourceCurrency, String targetCurrency, BigDecimal sourceAmount, BigDecimal fee,
            BigDecimal amountConverted, BigDecimal rate, BigDecimal targetAmount, LocalDate rateAsOf, Instant expiresAt,
            String status) {}

    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @PostMapping("/quotes")
    public ResponseEntity<QuoteView> create(@RequestBody CreateQuoteRequest r) {
        if (r.sourceCurrency() == null || r.targetCurrency() == null || r.sourceAmount() == null) {
            throw new IllegalArgumentException("sourceCurrency, targetCurrency and sourceAmount are required");
        }
        var quote = quotes.create(Currency.getInstance(r.sourceCurrency()), Currency.getInstance(r.targetCurrency()), r.sourceAmount());
        return ResponseEntity.created(URI.create("/quotes/" + quote.id())).body(view(quote));
    }

    @GetMapping("/quotes/{id}")
    public QuoteView get(@PathVariable UUID id) {
        return view(quotes.get(id));
    }

    @PostMapping("/quotes/{id}/consume")
    public QuoteView consume(@PathVariable UUID id, @RequestBody ConsumeRequest r) {
        return view(quotes.consume(id, r.consumerReference()));
    }

    private QuoteView view(Quote q) {
        return new QuoteView(q.id(), q.source().getCurrencyCode(), q.target().getCurrencyCode(),
                FxMath.toMajor(q.sourceAmountMinor(), q.source()), FxMath.toMajor(q.feeMinor(), q.source()),
                FxMath.toMajor(q.sourceAmountMinor() - q.feeMinor(), q.source()), q.rate(),
                FxMath.toMajor(q.targetAmountMinor(), q.target()), q.rateAsOf(), q.expiresAt(), q.status(quotes.now()).name());
    }

    @ExceptionHandler({IllegalArgumentException.class, UnsupportedCurrencyException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AmountTooSmallException.class)
    ProblemDetail tooSmall(AmountTooSmallException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(QuoteNotFoundException.class)
    ProblemDetail notFound(QuoteNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(QuoteAlreadyUsedException.class)
    ProblemDetail used(QuoteAlreadyUsedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(QuoteExpiredException.class)
    ProblemDetail expired(QuoteExpiredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
    }

    @ExceptionHandler(RatesUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(RatesUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }
}
