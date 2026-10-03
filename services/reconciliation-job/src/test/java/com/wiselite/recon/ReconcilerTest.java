package com.wiselite.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.RailsApi.StatementLine;
import com.wiselite.recon.Reconciler.LedgerTransfer;
import com.wiselite.recon.Reconciler.PayoutRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(1));
    private static final Duration GRACE = Duration.ofMinutes(10);

    @Test
    void consistentBooksHaveNoBreaks() {
        var done = transfer("COMPLETED", OLD);
        var refunded = transfer("REFUNDED", OLD);
        var inFlight = transfer("FUNDED", OLD);
        var breaks = Reconciler.reconcile(List.of(done, refunded, inFlight),
                List.of(payout(done, "SETTLED"), payout(refunded, "REJECTED"), payout(inFlight, "SUBMITTED")),
                List.of(line(done, RailsApi.SETTLED), line(refunded, RailsApi.REJECTED), line(inFlight, RailsApi.PENDING)),
                NOW, GRACE);
        assertThat(breaks).isEmpty();
    }

    @Test
    void refundedButPaidIsCritical() {
        var t = transfer("REFUNDED", OLD);
        assertThat(types(List.of(t), List.of(), List.of(line(t, RailsApi.SETTLED)))).containsExactly(BreakType.PAID_BUT_REFUNDED);
        assertThat(types(List.of(t), List.of(), List.of(line(t, RailsApi.PENDING)))).containsExactly(BreakType.PAID_BUT_REFUNDED);
    }

    @Test
    void completedTransfersMustBeSettledAtTheRail() {
        var t = transfer("COMPLETED", OLD);
        assertThat(types(List.of(t), List.of(), List.of())).containsExactly(BreakType.MISSING_AT_RAIL);
        assertThat(types(List.of(t), List.of(), List.of(line(t, RailsApi.REJECTED))))
                .containsExactly(BreakType.COMPLETED_BUT_NOT_SETTLED);
    }

    @Test
    void amountAndCurrencyMustMatch() {
        var t = transfer("COMPLETED", OLD);
        var wrong = new StatementLine("p", t.id().toString(), t.amountMinor() + 1, "EUR", RailsApi.SETTLED, OLD);
        var wrongCurrency = new StatementLine("p", t.id().toString(), t.amountMinor(), "GBP", RailsApi.SETTLED, OLD);
        assertThat(types(List.of(t), List.of(), List.of(wrong))).containsExactly(BreakType.AMOUNT_MISMATCH);
        assertThat(types(List.of(t), List.of(), List.of(wrongCurrency))).containsExactly(BreakType.AMOUNT_MISMATCH);
    }

    @Test
    void railPaymentsWeDontKnowAreCritical_butOnlyOnceOlderThanTheGraceWindow() {
        var stranger = new StatementLine("p", UUID.randomUUID().toString(), 100, "EUR", RailsApi.SETTLED, OLD);
        var young = new StatementLine("q", UUID.randomUUID().toString(), 100, "EUR", RailsApi.PENDING, NOW.minusSeconds(5));
        assertThat(types(List.of(), List.of(), List.of(stranger, young))).containsExactly(BreakType.UNKNOWN_AT_RAIL);
    }

    @Test
    void twoRailPaymentsForOneTransferAreDetected() {
        var t = transfer("COMPLETED", OLD);
        var second = new StatementLine("p2", t.id().toString(), t.amountMinor(), "EUR", RailsApi.SETTLED, OLD);
        assertThat(types(List.of(t), List.of(), List.of(line(t, RailsApi.SETTLED), second)))
                .containsExactly(BreakType.DUPLICATE_AT_RAIL);
    }

    @Test
    void inFlightTransferWithAFinalRailOutcomeIsStuck() {
        var t = transfer("PROCESSING", OLD);
        var breaks = Reconciler.reconcile(List.of(t), List.of(payout(t, "MANUAL_REVIEW")), List.of(line(t, RailsApi.SETTLED)),
                NOW, GRACE);
        assertThat(breaks).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo(BreakType.STUCK_RESOLVABLE);
            assertThat(b.details()).contains("MANUAL_REVIEW").contains("SETTLED");
        });
    }

    @Test
    void fundedLongAgoWithoutAnyPayoutIsMissingPayout_butYoungTransfersAreLeftAlone() {
        var old = transfer("FUNDED", OLD);
        var young = transfer("FUNDED", NOW.minusSeconds(30));
        assertThat(types(List.of(old, young), List.of(), List.of())).containsExactly(BreakType.MISSING_PAYOUT);
    }

    /** Whatever mix of consistent outcomes we generate, a clean world produces zero breaks. */
    @Property(tries = 200)
    void anyConsistentWorldReconcilesClean(@ForAll @IntRange(min = 0, max = 30) int completed,
            @ForAll @IntRange(min = 0, max = 30) int refunded, @ForAll @IntRange(min = 0, max = 30) int inFlight) {
        var transfers = new ArrayList<LedgerTransfer>();
        var payouts = new ArrayList<PayoutRecord>();
        var rail = new ArrayList<StatementLine>();
        for (int i = 0; i < completed; i++) {
            var t = transfer("COMPLETED", OLD);
            transfers.add(t);
            payouts.add(payout(t, "SETTLED"));
            rail.add(line(t, RailsApi.SETTLED));
        }
        for (int i = 0; i < refunded; i++) {
            var t = transfer("REFUNDED", OLD);
            transfers.add(t);
            if (i % 2 == 0) { // rejected by the rail, or refused before it ever reached the rail
                rail.add(line(t, RailsApi.REJECTED));
            }
        }
        for (int i = 0; i < inFlight; i++) {
            var t = transfer(i % 2 == 0 ? "FUNDED" : "PROCESSING", OLD);
            transfers.add(t);
            payouts.add(payout(t, "SUBMITTED"));
            rail.add(line(t, RailsApi.PENDING));
        }
        assertThat(Reconciler.reconcile(transfers, payouts, rail, NOW, GRACE)).isEmpty();
    }

    private static List<BreakType> types(List<LedgerTransfer> t, List<PayoutRecord> p, List<StatementLine> r) {
        return Reconciler.reconcile(t, p, r, NOW, GRACE).stream().map(Break::type).toList();
    }

    private static LedgerTransfer transfer(String state, Instant updatedAt) {
        return new LedgerTransfer(UUID.randomUUID(), 2_500, "EUR", state, updatedAt);
    }

    private static PayoutRecord payout(LedgerTransfer t, String status) {
        return new PayoutRecord(t.id(), status);
    }

    private static StatementLine line(LedgerTransfer t, String status) {
        return new StatementLine("pay-" + t.id(), t.id().toString(), t.amountMinor(), t.currency(), status, OLD);
    }
}
