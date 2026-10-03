package com.wiselite.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Recent decided transfers of one owner. Pruned to the longest rule window, so state stays bounded per owner. */
public record OwnerHistory(List<Entry> entries) {

    public record Entry(UUID transferId, long at, long amountMinor, String currency, String decision, List<String> reasons) {}

    static final OwnerHistory EMPTY = new OwnerHistory(List.of());

    Entry find(UUID transferId) {
        return entries.stream().filter(e -> e.transferId().equals(transferId)).findFirst().orElse(null);
    }

    OwnerHistory with(Entry entry, long keepAfter) {
        var kept = new ArrayList<Entry>();
        for (var e : entries) {
            if (e.at() > keepAfter) {
                kept.add(e);
            }
        }
        kept.add(entry);
        return new OwnerHistory(kept);
    }
}
