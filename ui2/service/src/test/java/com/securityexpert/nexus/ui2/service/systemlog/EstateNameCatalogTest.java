package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.IdentityTokens;
import com.securityexpert.nexus.ui2.service.privacy.EstateDictionarySource;

/**
 * The estate names of the masked view ({@code logmask-v5}, docs/design/LOG_MASKING_RISK_MEASUREMENT_2026_11_10.md §5):
 * names are collected from flat columns, the object inventory and policy snapshots by key and path, generic names are
 * dropped, a snapshot is re-read only when its version changed, a failed refresh keeps the previous dictionary, and
 * no load at all fails closed. Through {@link LogMasking} the names are masked in the line shapes the measurement
 * found: an administrator name with no trigger word, a policy name in free text and as a JSON value, a
 * virtual-system name as a JSON value, an object name inside a path, a domain name in {@code name (X)}.
 *
 * <p>Invented words and RFC 5737 addresses only.</p>
 */
class EstateNameCatalogTest {

    private static final byte[] KEY = ("0123456789abcdef".repeat(2) + "-test-" + "k" + "ey").getBytes(StandardCharsets.UTF_8);
    private final IdentityTokens tokens = new IdentityTokens(KEY);

    private static final String ADMIN = "zorvanek";
    private static final String SNAPSHOT_ADMIN = "pelmira";
    private static final String POLICY = "Quellmar Branch Standard";
    private static final String SECTION = "Ravensbrook Access";
    private static final String RULE = "Allow Thistledown";
    private static final String OBJECT = "Brindlewick_Net";
    private static final String INVENTORY_OBJECT = "Corvaline-Hosts";
    private static final String VSYS = "vsTarnholm";
    private static final String DOMAIN = "Ostervale";
    private static final String ZONE = "Glimmerdeck";

    private static final String METADATA = "{\"name\":\"" + POLICY + "\",\"vendor\":\"checkpoint\",\"id\":\"p-1\"}";
    private static final String SNAPSHOT = "{\"sections\":[{\"name\":\"" + SECTION + "\",\"rules\":[{\"name\":\"" + RULE
            + "\",\"uuid\":\"11111111-2222-3333-4444-555555555555\",\"source\":[\"" + OBJECT + "\"],\"destination\":[\"Any\"],"
            + "\"comments\":\"Wexmoor note\",\"last-modifier\":\"" + SNAPSHOT_ADMIN + "\"},{\"name\":\"Cleanup rule\"}]}],"
            + "\"objects\":[{\"name\":\"" + OBJECT + "\",\"type\":\"network\"},{\"name\":\"192.0.2.0/24\"}]}";

    /** A source over fixed documents that counts how often each snapshot is read. */
    private static final class FakeSource implements EstateNameCatalog.Source {
        final Map<String, String> versions = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Integer> reads = new java.util.concurrent.ConcurrentHashMap<>();
        volatile RuntimeException failure;

        FakeSource() {
            versions.put("p-1", "v1");
        }

        @Override
        public List<EstateNameCatalog.Named> flatNames() {
            if (failure != null) throw failure;
            return List.of(new EstateNameCatalog.Named(EstateNameCatalog.Kind.USER, ADMIN),
                    new EstateNameCatalog.Named(EstateNameCatalog.Kind.VSYS, VSYS),
                    new EstateNameCatalog.Named(EstateNameCatalog.Kind.VSYS, "vsys1"),
                    new EstateNameCatalog.Named(EstateNameCatalog.Kind.ZONE, ZONE),
                    new EstateNameCatalog.Named(EstateNameCatalog.Kind.ZONE, "inside"),
                    new EstateNameCatalog.Named(EstateNameCatalog.Kind.DOMAIN, DOMAIN),
                    new EstateNameCatalog.Named(EstateNameCatalog.Kind.USER, null));
        }

        @Override
        public List<String> objectInventoryDocuments() {
            return List.of("{\"objects\":[{\"name\":\"" + INVENTORY_OBJECT + "\",\"type\":\"group\"}]}", "{not json");
        }

        @Override
        public Map<String, String> policySnapshotVersions() {
            return Map.copyOf(versions);
        }

        @Override
        public Optional<String[]> policySnapshot(String policyId) {
            reads.merge(policyId, 1, Integer::sum);
            return Optional.of(new String[] {METADATA, SNAPSHOT});
        }
    }

    private static LogMasking masking(EstateNameCatalog catalog) {
        return new LogMasking(KEY, null, EstateDictionarySource.none(KEY), catalog);
    }

    @Test
    void namesAreCollectedByKeyAndPathAndGenericOrShapedValuesAreDropped() {
        FakeSource source = new FakeSource();
        EstateNameCatalog catalog = new EstateNameCatalog(KEY, source, Duration.ofHours(1));
        EstateDictionary d = catalog.get();
        EstateNameCatalog.Stats stats = catalog.stats();
        assertNotNull(stats);
        assertEquals(2, stats.kept().get(EstateNameCatalog.Kind.USER), "policy history and last-modifier");
        assertEquals(2, stats.kept().get(EstateNameCatalog.Kind.POLICY), "the policy and its section");
        assertEquals(1, stats.kept().get(EstateNameCatalog.Kind.RULE));
        assertEquals(2, stats.kept().get(EstateNameCatalog.Kind.OBJECT), "snapshot object and inventory object");
        assertEquals(1, stats.kept().get(EstateNameCatalog.Kind.VSYS), "vsys1 is generic");
        assertEquals(1, stats.kept().get(EstateNameCatalog.Kind.ZONE), "inside is generic");
        assertEquals(1, stats.kept().get(EstateNameCatalog.Kind.DOMAIN));
        assertTrue(stats.genericDropped() >= 4, "Cleanup rule, Any, vsys1, inside");
        assertTrue(stats.shapeDropped() >= 1, "the address");
        assertEquals(1, stats.snapshotFailures(), "the unparsable inventory document is counted, not fatal");
        assertEquals(10, d.nameCount());
        String comment = d.replacer().replace("Wexmoor", new com.securityexpert.nexus.ui2.sanitizer.core.Counts());
        assertEquals("Wexmoor", comment, "comments are not names");
    }

    @Test
    void theMaskedViewHidesEveryNameInTheShapesTheMeasurementFound() {
        LogMasking.Session s = masking(new EstateNameCatalog(KEY, new FakeSource(), Duration.ofHours(1))).session();
        assertEquals("logmask-v5", s.ruleSet());
        String[] lines = {
            "Nov 10 10:00:01 2026 gw-a kernel: " + ADMIN + " changed object",
            "Nov 10 10:00:02 2026 gw-a xpand[901]: User " + SNAPSHOT_ADMIN.toUpperCase(java.util.Locale.ROOT) + " logged out",
            "fwd: installing policy " + POLICY + " and " + SECTION + " done",
            "{\"vsys\":\"" + VSYS + "\",\"opaque\":\"Policy '" + POLICY.toLowerCase(java.util.Locale.ROOT) + "' committed\"}",
            "cpd: loading /var/opt/" + OBJECT + "/objects_5_0.C (" + INVENTORY_OBJECT + ")",
            "cpd: name (" + DOMAIN + ") zone \"" + ZONE + "\" rule " + RULE,
            "cpd: lookup (net_192.0.2.7) failed",
        };
        String[] values = {ADMIN, SNAPSHOT_ADMIN, POLICY, SECTION, VSYS, OBJECT, INVENTORY_OBJECT, DOMAIN, ZONE, RULE, "192.0.2.7"};
        for (String line : lines) {
            String masked = s.maskLine(line).toLowerCase(java.util.Locale.ROOT);
            for (String v : values) assertFalse(masked.contains(v.toLowerCase(java.util.Locale.ROOT)), "a name survived");
        }
        assertTrue(s.maskLine(lines[0]).contains(tokens.user(ADMIN)), "an administrator is the USR- token");
        assertTrue(s.maskLine(lines[2]).contains(tokens.payload(IdentityTokens.POLICY, POLICY) + " and "), "a typed policy token");
        assertTrue(s.maskLine(lines[4]).contains("/var/opt/" + tokens.payload(IdentityTokens.OBJECT, OBJECT) + "/objects_5_0.C"));
    }

    @Test
    void aSnapshotIsReReadOnlyWhenItsVersionChanges() throws Exception {
        FakeSource source = new FakeSource();
        EstateNameCatalog catalog = new EstateNameCatalog(KEY, source, Duration.ZERO);
        EstateDictionary first = catalog.get();
        assertEquals(1, source.reads.get("p-1"));
        waitForRefresh(catalog, first);
        assertEquals(1, source.reads.get("p-1"), "unchanged version: not read again");
        source.versions.put("p-1", "v2");
        source.versions.put("p-2", "v1");
        for (int i = 0; i < 200 && (source.reads.getOrDefault("p-2", 0) < 1 || source.reads.get("p-1") < 2); i++) {
            catalog.get();
            Thread.sleep(20);
        }
        Thread.sleep(100);
        catalog.get();
        Thread.sleep(100);
        assertEquals(2, source.reads.get("p-1"), "a changed version is read once");
        assertEquals(1, source.reads.get("p-2"), "a new snapshot is read once");
    }

    @Test
    void aFailedRefreshKeepsTheDictionaryAndNoLoadAtAllFailsClosed() throws Exception {
        FakeSource source = new FakeSource();
        EstateNameCatalog catalog = new EstateNameCatalog(KEY, source, Duration.ZERO);
        EstateDictionary first = catalog.get();
        source.failure = new IllegalStateException("database down");
        catalog.get();
        Thread.sleep(200);
        assertSame(first, catalog.get(), "the previous dictionary is served");

        FakeSource broken = new FakeSource();
        broken.failure = new IllegalStateException("database down");
        EstateNameCatalog never = new EstateNameCatalog(KEY, broken, Duration.ofHours(1));
        assertThrows(EstateNameCatalog.UnavailableException.class, never::get);
        assertThrows(LogMasking.MaskingUnavailableException.class, () -> masking(never).session(),
                "no masked page without the names pass");
    }

    @Test
    void genericNamesAreMadeOnlyOfVocabularyWordsAndNumbers() {
        var vocabulary = EstateNameCatalog.genericVocabulary();
        assertTrue(EstateNameCatalog.isGeneric("cleanup rule", vocabulary));
        assertTrue(EstateNameCatalog.isGeneric("rule 12", vocabulary));
        assertTrue(EstateNameCatalog.isGeneric("any", vocabulary));
        assertFalse(EstateNameCatalog.isGeneric("quellmar branch standard", vocabulary));
        assertFalse(EstateNameCatalog.isGeneric("brindlewick_net", vocabulary));
    }

    /** Waits until a background refresh has published a new dictionary (each load publishes a new object). */
    private static void waitForRefresh(EstateNameCatalog catalog, EstateDictionary before) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            EstateDictionary now = catalog.get();
            if (now != before) return;
            Thread.sleep(20);
        }
        throw new AssertionError("no refresh happened");
    }
}
