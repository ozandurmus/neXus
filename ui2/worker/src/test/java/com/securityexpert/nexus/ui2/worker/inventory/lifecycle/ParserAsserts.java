package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Locale;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;

/** Shared assertions of the parser tests: the values that must never be stored do not occur in any produced item. */
final class ParserAsserts {

    private ParserAsserts() {
    }

    static void noLeak(List<LicenseItem> items, String... forbidden) {
        for (LicenseItem item : items) {
            String all = (item.label() + "|" + item.detail() + "|" + item.memberRef() + "|" + item.state() + "|" + item.category())
                    .toLowerCase(Locale.ROOT);
            for (String value : forbidden) {
                assertFalse(all.contains(value.toLowerCase(Locale.ROOT)), "item leaks " + value + ": " + item);
            }
        }
    }

    static void noLeakPatches(List<LivePatchItem> items, String... forbidden) {
        for (LivePatchItem item : items) {
            String all = (item.patchId() + "|" + item.status() + "|" + item.mode() + "|" + item.refs()).toLowerCase(Locale.ROOT);
            for (String value : forbidden) {
                assertFalse(all.contains(value.toLowerCase(Locale.ROOT)), "patch leaks " + value + ": " + item);
            }
        }
    }

    static LicenseItem byLabel(List<LicenseItem> items, String label) {
        return items.stream().filter(i -> i.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no item labelled " + label + " in " + items));
    }
}
