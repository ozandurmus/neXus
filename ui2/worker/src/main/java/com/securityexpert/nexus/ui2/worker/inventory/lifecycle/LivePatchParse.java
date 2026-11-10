package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.util.List;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;

/** The result of the {@code cplp list} parser. */
public record LivePatchParse(ParseOutcome outcome, List<LivePatchItem> items, int skipped) {

    public LivePatchParse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static LivePatchParse unparsed() {
        return new LivePatchParse(ParseOutcome.UNPARSED, List.of(), 0);
    }
}
