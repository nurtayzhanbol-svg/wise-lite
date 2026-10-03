package com.wiselite.risk;

import com.wiselite.events.TransferStateChanged;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Window aggregate. Transfer ids are capped: state per window must stay bounded. */
public record Agg(long count, long sumMinor, List<UUID> transferIds, Set<UUID> owners) {

    static final int MAX_IDS = 50;

    static Agg empty() {
        return new Agg(0, 0, List.of(), Set.of());
    }

    Agg add(TransferStateChanged e) {
        var ids = new ArrayList<>(transferIds);
        if (ids.size() < MAX_IDS) {
            ids.add(e.transferId());
        }
        var o = new TreeSet<>(owners);
        o.add(e.ownerId());
        return new Agg(count + 1, Math.addExact(sumMinor, e.amountMinor()), ids, o);
    }
}
