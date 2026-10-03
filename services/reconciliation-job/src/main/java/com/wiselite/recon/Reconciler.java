package com.wiselite.recon;

import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.RailsApi.StatementLine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure matching logic (no I/O), so every rule is unit-testable.
 *
 * <p>The three sources can't be read at one instant. Records younger than {@code cutoff - grace}
 * may still be in flight, so in-flight rules skip them; the next run judges them.
 */
public final class Reconciler {

    public record LedgerTransfer(UUID id, long amountMinor, String currency, String state, Instant updatedAt) {}

    public record PayoutRecord(UUID transferId, String status) {}

    private Reconciler() {}

    public static List<Break> reconcile(List<LedgerTransfer> transfers, List<PayoutRecord> payouts, List<StatementLine> rail,
            Instant cutoff, Duration grace) {
        var settledBefore = cutoff.minus(grace);
        var breaks = new ArrayList<Break>();

        Map<String, StatementLine> railByReference = new LinkedHashMap<>();
        for (var line : rail) {
            var previous = railByReference.putIfAbsent(line.reference(), line);
            if (previous != null) {
                breaks.add(Break.of(BreakType.DUPLICATE_AT_RAIL, line.reference(),
                        "rail payments " + previous.paymentId() + " and " + line.paymentId()));
            }
        }
        var payoutByTransfer = payouts.stream().collect(Collectors.toMap(PayoutRecord::transferId, Function.identity()));

        Set<String> known = new HashSet<>();
        for (var t : transfers) {
            var ref = t.id().toString();
            known.add(ref);
            var line = railByReference.get(ref);

            if (line != null && (line.amountMinor() != t.amountMinor() || !line.currency().equals(t.currency()))) {
                breaks.add(Break.of(BreakType.AMOUNT_MISMATCH, ref, "ours " + t.amountMinor() + " " + t.currency()
                        + ", rail " + line.amountMinor() + " " + line.currency()));
            }
            switch (t.state()) {
                case "COMPLETED" -> {
                    if (line == null) {
                        breaks.add(Break.of(BreakType.MISSING_AT_RAIL, ref, "transfer COMPLETED, no rail payment"));
                    } else if (!RailsApi.SETTLED.equals(line.status())) {
                        breaks.add(Break.of(BreakType.COMPLETED_BUT_NOT_SETTLED, ref, "transfer COMPLETED, rail " + line.status()));
                    }
                }
                case "REFUNDED" -> {
                    if (line != null && !RailsApi.REJECTED.equals(line.status())) {
                        breaks.add(Break.of(BreakType.PAID_BUT_REFUNDED, ref, "transfer REFUNDED, rail " + line.status()));
                    }
                }
                case "FUNDED", "HELD" -> {
                    // Approval precedes payout precedes rail payment. A rail line older than the cutoff (so older than our
                    // transfers snapshot) for a transfer that is still unapproved in that snapshot is a gate violation.
                    if (line != null && line.createdAt().isBefore(cutoff)) {
                        breaks.add(Break.of(BreakType.PAID_BEFORE_APPROVAL, ref, "transfer " + t.state() + ", rail " + line.status()));
                    }
                }
                case "APPROVED", "PROCESSING" -> {
                    if (!t.updatedAt().isBefore(settledBefore)) {
                        break;
                    }
                    var payout = payoutByTransfer.get(t.id());
                    if (line != null && !RailsApi.PENDING.equals(line.status())) {
                        breaks.add(Break.of(BreakType.STUCK_RESOLVABLE, ref, "transfer " + t.state() + ", payout "
                                + (payout == null ? "none" : payout.status()) + ", rail " + line.status()));
                    } else if (payout == null && line == null) {
                        breaks.add(Break.of(BreakType.MISSING_PAYOUT, ref, "transfer " + t.state() + " since " + t.updatedAt()));
                    }
                }
                default -> { }
            }
        }

        for (var line : railByReference.values()) {
            // A payment's transfer is always created before it; a younger line may postdate our snapshot.
            if (!known.contains(line.reference()) && line.createdAt().isBefore(settledBefore)) {
                breaks.add(Break.of(BreakType.UNKNOWN_AT_RAIL, line.reference(),
                        "rail payment " + line.paymentId() + " " + line.amountMinor() + " " + line.currency()));
            }
        }
        return breaks;
    }
}
