package com.wiselite.transfer.risk;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.events.RiskDecision;
import com.wiselite.events.Topics;
import com.wiselite.transfer.IntegrationTest;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.AccountType;
import com.wiselite.transfer.ledger.JournalEntry;
import com.wiselite.transfer.ledger.JournalEntryType;
import com.wiselite.transfer.ledger.LedgerService;
import com.wiselite.transfer.ledger.Money;
import com.wiselite.transfer.transfer.IllegalStateTransitionException;
import com.wiselite.transfer.transfer.Transfer;
import com.wiselite.transfer.transfer.TransferService;
import com.wiselite.transfer.transfer.TransferState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The risk gate inside transfer-service: decisions, duplicates, late/conflicting decisions, timeouts,
 * operator verdicts, crashes and races. "A payout exists" is observed as "an APPROVED event is in the
 * outbox", because that event is the only thing payout-worker acts on.
 */
@IntegrationTest
class RiskGateIT {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired TransferService transfers;
    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired RiskDecisionHandler handler;
    @Autowired RiskOperatorService operators;
    @Autowired RiskTimeoutService timeouts;
    @Autowired RiskDecisionLog log;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;

    @Test
    void allowApprovesAndEmitsTheOnlyPayoutTrigger() {
        var t = fundedTransfer();

        assertThat(handler.handle(decision(t, RiskDecision.ALLOW))).isEqualTo(RiskDecisionHandler.Outcome.APPLIED);

        assertThat(state(t)).isEqualTo(TransferState.APPROVED);
        assertThat(approvedEvents(t)).isEqualTo(1);
    }

    @Test
    void reviewHoldsWithoutAnyPayoutTrigger() {
        var t = fundedTransfer();

        handler.handle(decision(t, RiskDecision.REVIEW));

        assertThat(state(t)).isEqualTo(TransferState.HELD);
        assertThat(approvedEvents(t)).isZero();
        assertThat(accounts.balance(t.sourceAccountId())).isEqualTo(Money.of("60.00", "EUR"));
    }

    @Test
    void blockRefundsExactlyOnce() {
        var t = fundedTransfer();

        handler.handle(decision(t, RiskDecision.BLOCK));

        assertThat(state(t)).isEqualTo(TransferState.REFUNDED);
        assertThat(approvedEvents(t)).isZero();
        assertThat(refunds(t)).isEqualTo(1);
        assertThat(accounts.balance(t.sourceAccountId())).isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void redeliveredDecisionIsADuplicate() {
        var t = fundedTransfer();
        var d = decision(t, RiskDecision.BLOCK);

        handler.handle(d);
        assertThat(handler.handle(d)).isEqualTo(RiskDecisionHandler.Outcome.DUPLICATE);
        assertThat(handler.handle(d)).isEqualTo(RiskDecisionHandler.Outcome.DUPLICATE);

        assertThat(refunds(t)).isEqualTo(1);
        assertThat(accounts.balance(t.sourceAccountId())).isEqualTo(Money.of("100.00", "EUR"));
    }

    /** A second, different decision (rules re-run, replayed topic with new ids) never overrides the first. */
    @Test
    void conflictingLateDecisionsAreRecordedButIgnored() {
        var blocked = fundedTransfer();
        handler.handle(decision(blocked, RiskDecision.BLOCK));
        var allowed = fundedTransfer();
        handler.handle(decision(allowed, RiskDecision.ALLOW));
        var held = fundedTransfer();
        handler.handle(decision(held, RiskDecision.REVIEW));

        assertThat(handler.handle(otherDecision(blocked, RiskDecision.ALLOW))).isEqualTo(RiskDecisionHandler.Outcome.IGNORED);
        assertThat(handler.handle(otherDecision(allowed, RiskDecision.BLOCK))).isEqualTo(RiskDecisionHandler.Outcome.IGNORED);
        assertThat(handler.handle(otherDecision(held, RiskDecision.ALLOW))).isEqualTo(RiskDecisionHandler.Outcome.IGNORED);

        assertThat(state(blocked)).as("a late ALLOW cannot resurrect a refunded transfer").isEqualTo(TransferState.REFUNDED);
        assertThat(approvedEvents(blocked)).isZero();
        assertThat(state(allowed)).isEqualTo(TransferState.APPROVED);
        assertThat(refunds(allowed)).isZero();
        assertThat(state(held)).as("only an operator releases a HELD transfer").isEqualTo(TransferState.HELD);
        assertThat(approvedEvents(held)).isZero();
        assertThat(log.list(held.id())).extracting(RiskDecisionLog.Entry::applied).containsExactly(true, false);
    }

    @Test
    void decisionAfterCompletionChangesNothing() {
        var t = fundedTransfer();
        handler.handle(decision(t, RiskDecision.ALLOW));
        transfers.markProcessing(t.id());
        transfers.complete(t.id());

        assertThat(handler.handle(otherDecision(t, RiskDecision.BLOCK))).isEqualTo(RiskDecisionHandler.Outcome.IGNORED);

        assertThat(state(t)).isEqualTo(TransferState.COMPLETED);
        assertThat(refunds(t)).isZero();
    }

    /** Crash after all the writes but before commit: nothing survives, and the redelivered record applies once. */
    @Test
    void crashMidDecisionLeavesNoTraceAndRedeliveryAppliesOnce() {
        var t = fundedTransfer();
        var d = decision(t, RiskDecision.BLOCK);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            handler.handle(d);
            throw new IllegalStateException("process killed before commit");
        })).hasMessageContaining("killed");

        assertThat(state(t)).isEqualTo(TransferState.FUNDED);
        assertThat(refunds(t)).isZero();
        assertThat(log.list(t.id())).isEmpty();

        assertThat(handler.handle(d)).isEqualTo(RiskDecisionHandler.Outcome.APPLIED);
        assertThat(handler.handle(d)).isEqualTo(RiskDecisionHandler.Outcome.DUPLICATE);
        assertThat(refunds(t)).isEqualTo(1);
    }

    @Test
    void decisionsArriveOverKafka() throws Exception {
        var t = fundedTransfer();
        var d = decision(t, RiskDecision.REVIEW);
        kafka.send(Topics.RISK_DECISIONS, t.id().toString(), json.writeValueAsString(d)).get();
        kafka.send(Topics.RISK_DECISIONS, t.id().toString(), json.writeValueAsString(d)).get(); // redelivery

        await().atMost(Duration.ofSeconds(20)).until(() -> state(t) == TransferState.HELD);
        await().atMost(Duration.ofSeconds(10)).until(() -> processed(d.decisionId()));
        assertThat(log.list(t.id())).hasSize(1);
    }

    // --- timeout: fail closed ---

    @Test
    void missingDecisionHoldsTheTransfer_andTheLateDecisionDoesNotRelease() {
        var t = fundedTransfer();

        timeouts.holdOverdue(Duration.ZERO);

        assertThat(state(t)).isEqualTo(TransferState.HELD);
        assertThat(transfers.history(t.id()).getLast().reason()).startsWith("RISK_TIMEOUT");
        assertThat(handler.handle(decision(t, RiskDecision.ALLOW))).isEqualTo(RiskDecisionHandler.Outcome.IGNORED);
        assertThat(state(t)).isEqualTo(TransferState.HELD);
        assertThat(approvedEvents(t)).isZero();
    }

    @Test
    void youngTransfersAreNotTimedOut() {
        var t = fundedTransfer();
        timeouts.holdOverdue(Duration.ofMinutes(5));
        assertThat(state(t)).isEqualTo(TransferState.FUNDED);
    }

    /** Sweeper and decision race on the row lock: exactly one of them acts, never both. */
    @Test
    void timeoutRacingTheDecisionHasOneWinner() throws Exception {
        for (int i = 0; i < 10; i++) {
            var t = fundedTransfer();
            var results = race(List.of(
                    () -> handler.handle(decision(t, RiskDecision.ALLOW)).name(),
                    () -> String.valueOf(timeouts.holdOverdue(Duration.ZERO))));
            var s = state(t);
            assertThat(s).isIn(TransferState.APPROVED, TransferState.HELD);
            assertThat(approvedEvents(t)).isEqualTo(s == TransferState.APPROVED ? 1 : 0);
            assertThat(log.list(t.id()).stream().filter(RiskDecisionLog.Entry::applied)).hasSize(1);
            assertThat(results).isNotEmpty();
        }
    }

    // --- operator verdicts ---

    @Test
    void releaseIsIdempotentUnderConcurrency() throws Exception {
        var t = heldTransfer();

        var results = race(Collections.nCopies(16, () -> operators.release(t.id(), "alice", "customer verified").state().name()));

        assertThat(results).containsOnly("APPROVED");
        assertThat(approvedEvents(t)).as("one APPROVED event → at most one payout").isEqualTo(1);
        assertThat(transfers.history(t.id())).extracting(Transfer.StateChange::to)
                .containsExactly(TransferState.CREATED, TransferState.FUNDED, TransferState.HELD, TransferState.APPROVED);
    }

    @Test
    void rejectIsIdempotentUnderConcurrency_andRefundsOnce() throws Exception {
        var t = heldTransfer();

        var results = race(Collections.nCopies(16, () -> operators.reject(t.id(), "alice", "mule").state().name()));

        assertThat(results).containsOnly("REFUNDED");
        assertThat(refunds(t)).isEqualTo(1);
        assertThat(approvedEvents(t)).isZero();
        assertThat(accounts.balance(t.sourceAccountId())).isEqualTo(Money.of("100.00", "EUR"));
    }

    /** Adversarial: two operators click different buttons at the same moment. */
    @Test
    void releaseRacingRejectHasExactlyOneWinner() throws Exception {
        for (int i = 0; i < 10; i++) {
            var t = heldTransfer();
            var tasks = new ArrayList<Callable<String>>();
            for (int k = 0; k < 4; k++) {
                tasks.add(() -> "release:" + attempt(() -> operators.release(t.id(), "alice", null)));
                tasks.add(() -> "reject:" + attempt(() -> operators.reject(t.id(), "bob", null)));
            }
            var results = race(tasks);
            var s = state(t);
            if (s == TransferState.APPROVED) {
                assertThat(results).filteredOn(r -> r.startsWith("reject")).containsOnly("reject:409");
                assertThat(approvedEvents(t)).isEqualTo(1);
                assertThat(refunds(t)).isZero();
            } else {
                assertThat(s).isEqualTo(TransferState.REFUNDED);
                assertThat(results).filteredOn(r -> r.startsWith("release")).containsOnly("release:409");
                assertThat(approvedEvents(t)).isZero();
                assertThat(refunds(t)).isEqualTo(1);
            }
        }
    }

    @Test
    void operatorsCanOnlyDecideHeldTransfers() {
        var waiting = fundedTransfer();
        assertThatThrownBy(() -> operators.release(waiting.id(), "alice", null)).isInstanceOf(IllegalStateTransitionException.class);

        var autoApproved = fundedTransfer();
        handler.handle(decision(autoApproved, RiskDecision.ALLOW));
        assertThatThrownBy(() -> operators.release(autoApproved.id(), "alice", null)).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> operators.reject(autoApproved.id(), "alice", null))
                .as("an approved transfer may already be at the rail: no cancel")
                .isInstanceOf(IllegalStateTransitionException.class);

        var released = heldTransfer();
        operators.release(released.id(), "alice", null);
        assertThatThrownBy(() -> operators.reject(released.id(), "bob", null)).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(approvedEvents(released)).isEqualTo(1);
    }

    // --- helpers ---

    private String attempt(Callable<Transfer> call) {
        try {
            return call.call().state().name();
        } catch (IllegalStateTransitionException e) {
            return "409";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> race(List<Callable<String>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var futures = new ArrayList<Future<String>>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            var results = new ArrayList<String>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    private Transfer fundedTransfer() {
        var account = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, EUR);
        ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup-" + account.id(),
                debit(funding.id(), Money.of("100.00", "EUR")), credit(account.id(), Money.of("100.00", "EUR"))));
        return transfers.create(account.ownerId(), new TransferService.CreateTransfer(account.id(), Money.of("40.00", "EUR"),
                new Transfer.Recipient("Bob", "DE89370400440532013000")));
    }

    private Transfer heldTransfer() {
        var t = fundedTransfer();
        handler.handle(decision(t, RiskDecision.REVIEW));
        return t;
    }

    private static RiskDecision decision(Transfer t, String verdict) {
        return new RiskDecision(RiskDecision.idFor(t.id()), t.id(), verdict, List.of("TEST"), "test", Instant.now());
    }

    private static RiskDecision otherDecision(Transfer t, String verdict) {
        return new RiskDecision(UUID.randomUUID(), t.id(), verdict, List.of("LATE"), "test-v2", Instant.now());
    }

    private TransferState state(Transfer t) {
        return transfers.get(t.id()).state();
    }

    private long approvedEvents(Transfer t) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_key = ? AND payload LIKE '%\"toState\":\"APPROVED\"%'")
                .param(t.id().toString()).query(Long.class).single();
    }

    private long refunds(Transfer t) {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE reference = ?")
                .param("transfer:" + t.id() + ":refund").query(Long.class).single();
    }

    private boolean processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = ?").param(eventId).query(Long.class).single() == 1;
    }
}
