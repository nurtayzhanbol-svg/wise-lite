package com.wiselite.risk;

import com.wiselite.events.RiskDecision;
import com.wiselite.events.TransferStateChanged;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure decision function: same rules, history and event → same verdict.
 * BLOCK beats REVIEW beats ALLOW. Velocity counts every attempt (blocked attempts are a signal too);
 * rolling volume counts only money that wasn't blocked.
 */
public final class RiskPolicy {

    public static final String RULES_VERSION = "m10-v1";

    public record Verdict(String decision, List<String> reasons) {}

    private RiskPolicy() {}

    public static Verdict decide(RiskRules r, OwnerHistory history, TransferStateChanged e, long at) {
        var block = new ArrayList<String>();
        if (e.amountMinor() >= r.blockAmountMinor()) {
            block.add("AMOUNT_ABOVE_BLOCK_LIMIT");
        }
        if (r.blockedIbans().stream().map(RiskPolicy::normalise).anyMatch(normalise(e.recipientIban())::equals)) {
            block.add("RECIPIENT_BLOCKLISTED");
        }
        if (!block.isEmpty()) {
            return new Verdict(RiskDecision.BLOCK, block);
        }
        var review = new ArrayList<String>();
        if (e.amountMinor() >= r.reviewAmountMinor()) {
            review.add("AMOUNT_ABOVE_REVIEW_LIMIT");
        }
        long velocityFrom = at - r.velocityWindow().toMillis();
        long recent = history.entries().stream().filter(x -> x.at() > velocityFrom && x.at() <= at).count() + 1;
        if (recent > r.maxTransfersPerWindow()) {
            review.add("VELOCITY");
        }
        long volumeFrom = at - r.volumeWindow().toMillis();
        long volume = e.amountMinor() + history.entries().stream()
                .filter(x -> x.at() > volumeFrom && x.at() <= at && x.currency().equals(e.currency())
                        && !RiskDecision.BLOCK.equals(x.decision()))
                .mapToLong(OwnerHistory.Entry::amountMinor).sum();
        if (volume > r.maxVolumeMinor()) {
            review.add("DAILY_VOLUME");
        }
        return review.isEmpty() ? new Verdict(RiskDecision.ALLOW, List.of()) : new Verdict(RiskDecision.REVIEW, review);
    }

    static String normalise(String iban) {
        return iban == null ? "" : iban.replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
