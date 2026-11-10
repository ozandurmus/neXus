package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.util.List;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

/**
 * The result of a license parser: an outcome, the items, and how many understood lines were skipped because no clean
 * label could be made from them.
 */
public record LicenseParse(ParseOutcome outcome, List<LicenseItem> items, int skipped) {

    public LicenseParse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static LicenseParse unparsed() {
        return new LicenseParse(ParseOutcome.UNPARSED, List.of(), 0);
    }

    /** Blank raw: EMPTY. Items: COLLECTED. Otherwise UNPARSED (an answer none of whose lines produced an item). */
    static LicenseParse of(String raw, List<LicenseItem> items, int skipped) {
        if (raw == null || raw.isBlank()) {
            return new LicenseParse(ParseOutcome.EMPTY, List.of(), 0);
        }
        return new LicenseParse(items.isEmpty() ? ParseOutcome.UNPARSED : ParseOutcome.COLLECTED, items, skipped);
    }

    /** Recognised, but names nothing: the stored items are kept. */
    static LicenseParse empty(int skipped) {
        return new LicenseParse(ParseOutcome.EMPTY, List.of(), skipped);
    }
}
