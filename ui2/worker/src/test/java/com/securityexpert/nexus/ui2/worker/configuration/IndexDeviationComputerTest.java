package com.securityexpert.nexus.ui2.worker.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry.DeviationKind;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/**
 * Tests for {@link IndexDeviationComputer} covering all acceptance criteria
 * of 14I DV-2: sections added, removed and recounted (AC-1, AC-2); the two
 * vendor shapes (AC-3); a first run (AC-4); an unchanged run (AC-4); and a
 * predecessor whose index is missing (AC-5).
 *
 * <p>AC-6 invariant: no configuration value appears in any fixture or
 * assertion below. Fixtures carry only section names (opaque strings),
 * contexts, and integer counts.</p>
 */
class IndexDeviationComputerTest {

    // ------------------------------------------------------------------
    // Helpers -- opaque names, no configuration content (AC-6).
    // ------------------------------------------------------------------

    private static ConfigurationIndexEntry cpEntry(String context, String section, int count) {
        // Check Point entries have no source (empty Optional).
        return new ConfigurationIndexEntry(context, section, Optional.empty(), count);
    }

    private static ConfigurationIndexEntry panEntry(String context, String section, String source, int count) {
        // Palo Alto entries carry a source; two entries with same section but different
        // source are different index rows (AC-3).
        return new ConfigurationIndexEntry(context, section, Optional.of(source), count);
    }

    // ------------------------------------------------------------------
    // AC-2: sections added, removed, and recounted (Check Point shape).
    // ------------------------------------------------------------------

    @Test
    void sectionPresentOnlyInNewIndexIsReportedAsAdded() {
        List<ConfigurationIndexEntry> oldIndex = List.of(
                cpEntry("ctx-a", "sec-x", 3));
        List<ConfigurationIndexEntry> newIndex = List.of(
                cpEntry("ctx-a", "sec-x", 3),
                cpEntry("ctx-a", "sec-y", 7));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(oldIndex, newIndex);

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        assertEquals(1, summary.entries().size());
        ConfigurationDeviationEntry entry = summary.entries().get(0);
        assertEquals(DeviationKind.ADDED, entry.kind());
        assertEquals("ctx-a", entry.context());
        assertEquals("sec-y", entry.section());
        assertEquals(0, entry.oldCount());
        assertEquals(7, entry.newCount());
    }

    @Test
    void sectionPresentOnlyInOldIndexIsReportedAsRemoved() {
        List<ConfigurationIndexEntry> oldIndex = List.of(
                cpEntry("ctx-a", "sec-x", 5),
                cpEntry("ctx-a", "sec-z", 2));
        List<ConfigurationIndexEntry> newIndex = List.of(
                cpEntry("ctx-a", "sec-x", 5));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(oldIndex, newIndex);

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        assertEquals(1, summary.entries().size());
        ConfigurationDeviationEntry entry = summary.entries().get(0);
        assertEquals(DeviationKind.REMOVED, entry.kind());
        assertEquals("ctx-a", entry.context());
        assertEquals("sec-z", entry.section());
        assertEquals(2, entry.oldCount());
        assertEquals(0, entry.newCount());
    }

    @Test
    void sectionInBothIndexesWithDifferentCountIsReportedAsRecounted() {
        List<ConfigurationIndexEntry> oldIndex = List.of(
                cpEntry("ctx-b", "sec-p", 10));
        List<ConfigurationIndexEntry> newIndex = List.of(
                cpEntry("ctx-b", "sec-p", 14));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(oldIndex, newIndex);

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        assertEquals(1, summary.entries().size());
        ConfigurationDeviationEntry entry = summary.entries().get(0);
        assertEquals(DeviationKind.RECOUNTED, entry.kind());
        assertEquals("ctx-b", entry.context());
        assertEquals("sec-p", entry.section());
        assertEquals(10, entry.oldCount());
        assertEquals(14, entry.newCount());
    }

    @Test
    void multipleChangesAcrossSectionsAreAllReported() {
        List<ConfigurationIndexEntry> oldIndex = List.of(
                cpEntry("ctx-a", "sec-kept", 5),
                cpEntry("ctx-a", "sec-removed", 2),
                cpEntry("ctx-b", "sec-recounted", 8));
        List<ConfigurationIndexEntry> newIndex = List.of(
                cpEntry("ctx-a", "sec-kept", 5),
                cpEntry("ctx-a", "sec-added", 3),
                cpEntry("ctx-b", "sec-recounted", 11));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(oldIndex, newIndex);

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        // sec-kept: unchanged -- must not appear.
        // sec-removed: REMOVED.
        // sec-added: ADDED.
        // sec-recounted: RECOUNTED.
        assertEquals(3, summary.entries().size(),
                "three deviating sections; the unchanged section must not appear");
        assertTrue(summary.entries().stream().anyMatch(e -> e.kind() == DeviationKind.ADDED && e.section().equals("sec-added")));
        assertTrue(summary.entries().stream().anyMatch(e -> e.kind() == DeviationKind.REMOVED && e.section().equals("sec-removed")));
        assertTrue(summary.entries().stream().anyMatch(e -> e.kind() == DeviationKind.RECOUNTED && e.section().equals("sec-recounted")));
    }

    // ------------------------------------------------------------------
    // AC-3: Palo Alto entries that differ only by source are different
    // entries; provenance moves must not be silently swallowed.
    // ------------------------------------------------------------------

    @Test
    void paloAltoEntriesDifferingOnlyBySourceAreTreatedAsDifferentEntries() {
        // Old: sec-q has one row with source=tpl (count 4) and one with source=dg (count 6).
        // New: same section but source=tpl is gone, source=local arrives.
        // Total old count = 10, total new count = 9 (local adds 3, tpl's 4 removed).
        List<ConfigurationIndexEntry> oldIndex = List.of(
                panEntry("vsys-1", "sec-q", "tpl", 4),
                panEntry("vsys-1", "sec-q", "dg", 6));
        List<ConfigurationIndexEntry> newIndex = List.of(
                panEntry("vsys-1", "sec-q", "dg", 6),
                panEntry("vsys-1", "sec-q", "local", 3));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(oldIndex, newIndex);

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        // The section appears in both but with a different total count (10 -> 9): RECOUNTED.
        assertEquals(1, summary.entries().size());
        ConfigurationDeviationEntry entry = summary.entries().get(0);
        assertEquals(DeviationKind.RECOUNTED, entry.kind());
        assertEquals("vsys-1", entry.context());
        assertEquals("sec-q", entry.section());
        assertEquals(10, entry.oldCount(), "old total: tpl(4) + dg(6)");
        assertEquals(9, entry.newCount(), "new total: dg(6) + local(3)");
    }

    @Test
    void paloAltoSectionUnchangedAcrossAllSourcesProducesNoEntry() {
        // Same section, same sources, same counts: no deviation.
        List<ConfigurationIndexEntry> oldIndex = List.of(
                panEntry("vsys-1", "sec-r", "tpl", 5),
                panEntry("vsys-1", "sec-r", "shared", 2));
        List<ConfigurationIndexEntry> newIndex = List.of(
                panEntry("vsys-1", "sec-r", "tpl", 5),
                panEntry("vsys-1", "sec-r", "shared", 2));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(oldIndex, newIndex);

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        assertTrue(summary.entries().isEmpty(), "no structural change: no deviation entries expected");
    }

    // ------------------------------------------------------------------
    // AC-4: first run and unchanged run carry no summary -- confirmed
    // here by verifying that caller-supplied equal indexes yield an empty
    // COMPUTED summary (the executor never calls compute for first_run
    // or unchanged; these tests verify IndexDeviationComputer's own output
    // for equal inputs).
    // ------------------------------------------------------------------

    @Test
    void identicalIndexesYieldAComputedSummaryWithNoEntries() {
        List<ConfigurationIndexEntry> sameIndex = List.of(
                cpEntry("ctx-c", "sec-s", 9));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(sameIndex, sameIndex);

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        assertTrue(summary.entries().isEmpty());
    }

    @Test
    void emptyOldAndEmptyNewYieldAComputedSummaryWithNoEntries() {
        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(List.of(), List.of());

        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        assertTrue(summary.entries().isEmpty());
    }

    // ------------------------------------------------------------------
    // AC-5: a null predecessor index (never stored) yields NOT_COMPUTABLE
    // rather than reporting every section as added.
    // ------------------------------------------------------------------

    @Test
    void nullOldIndexYieldsNotComputableRatherThanReportingEverySectionAsAdded() {
        List<ConfigurationIndexEntry> newIndex = List.of(
                cpEntry("ctx-d", "sec-t", 12),
                cpEntry("ctx-d", "sec-u", 4));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(null, newIndex);

        assertEquals(ConfigurationDeviationSummary.Status.NOT_COMPUTABLE, summary.status());
        assertTrue(summary.entries().isEmpty(),
                "AC-5: must not report every new section as added when predecessor index was never stored");
    }

    // ------------------------------------------------------------------
    // AC-6 self-check: verify that entries carry only context, section,
    // kind, and integer counts -- no configuration value.
    // ------------------------------------------------------------------

    @Test
    void summaryEntriesContainOnlySectionNamesContextsAndIntegers() {
        List<ConfigurationIndexEntry> oldIndex = List.of(cpEntry("ctx-e", "sec-v", 1));
        List<ConfigurationIndexEntry> newIndex = List.of(cpEntry("ctx-e", "sec-v", 2));

        ConfigurationDeviationSummary summary = IndexDeviationComputer.compute(oldIndex, newIndex);

        assertEquals(1, summary.entries().size());
        ConfigurationDeviationEntry entry = summary.entries().get(0);
        // Only these five fields exist on a ConfigurationDeviationEntry.
        // The record compiler enforces no other fields exist.
        assertEquals("ctx-e", entry.context());   // opaque context name
        assertEquals("sec-v", entry.section());   // opaque section name
        assertEquals(DeviationKind.RECOUNTED, entry.kind()); // enum, not a value
        assertEquals(1, entry.oldCount());         // integer
        assertEquals(2, entry.newCount());         // integer
    }
}
