package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PublishRequest;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.ZoneInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** ZONE_MATRIX_CONTRACT.md sections 3.4 and 3.5 (ZM-2, ZM-3, ZM-22, ZM-29, ZM-33). Synthetic principals and ranges. */
class ZoneMatrixServiceTest {
    private final Memory store = new Memory();
    private final Set<String> compliance = new HashSet<>(Set.of("compliance-1", "compliance-2"));
    private final Set<String> roots = Set.of("root-local");
    private final ZoneMatrixService service = new ZoneMatrixService(store, new ZoneMatrixService.Principals() {
        @Override public boolean holdsComplianceAdmin(String actor) { return compliance.contains(actor); }
        @Override public boolean isRoot(String actor) { return roots.contains(actor); }
    }, new ObjectMapper(), Clock.fixed(Instant.parse("2026-11-14T09:00:00Z"), ZoneOffset.UTC));

    private Map<String, Object> create(String actor, Long revision, String name, String... blocks) {
        return service.createZone(actor, revision, new ZoneInput(name, "synthetic", null, List.of(blocks)));
    }

    private static long rev(Map<String, Object> result) { return ((Number) result.get("revision")).longValue(); }

    @Test void theFirstEditCreatesADraftAndEveryEditNeedsTheRevisionItSaw() {
        var first = create("security-1", null, "Z-APP", "198.51.100.0/24");
        assertEquals(2L, rev(first));
        assertEquals("REQUEST_CHANGED", assertThrows(Refused.class, () -> create("security-1", null, "Z-DB", "203.0.113.0/25")).code);
        assertEquals("REQUEST_CHANGED", assertThrows(Refused.class, () -> create("security-1", 1L, "Z-DB", "203.0.113.0/25")).code);
        var second = create("security-1", 2L, "Z-DB", "203.0.113.0/25");
        assertEquals(3L, rev(second));
        assertEquals(List.of("DRAFT_CREATE", "ZONE_CREATE", "ZONE_CREATE"), store.kinds(store.draftId()));
    }

    @Test void invalidEditsChangeNothing() {
        var first = create("security-1", null, "Z-APP", "198.51.100.0/24");
        Refused blocks = assertThrows(Refused.class, () -> create("security-1", rev(first), "Z-X", "198.51.100.7/24", "bad"));
        assertEquals("INVALID_BLOCKS", blocks.code);
        assertEquals(2, ((List<?>) blocks.details.get("errors")).size());
        assertFalse(blocks.getMessage().contains("198.51.100.7"), "a refusal never echoes the refused value");
        Refused duplicate = assertThrows(Refused.class, () -> create("security-1", rev(first), "Z-DB", "198.51.100.0/24"));
        assertEquals("ZONE_BLOCK_DUPLICATE", duplicate.code);
        assertEquals(Set.of("Z-APP", "Z-DB"), Set.copyOf((List<?>) duplicate.details.get("zones")));
        assertEquals("ZONE_NAME_DUPLICATE", assertThrows(Refused.class, () -> create("security-1", rev(first), "z-app")).code);
        assertEquals("INVALID_ZONE_NAME", assertThrows(Refused.class, () -> create("security-1", rev(first), "Unassigned")).code);
        assertEquals(2L, store.draft().orElseThrow().revision());
    }

    @Test void publishingNeedsTwoPeopleAndACompliancePerson() {
        var draft = create("security-1", null, "Z-APP", "198.51.100.0/24");
        var check = new PublishRequest(rev(draft), (String) draft.get("content_sha256"), null);
        assertEquals("SELF_APPROVAL", assertThrows(Refused.class, () -> service.publish("security-1", false, check)).code);
        assertEquals("SELF_APPROVAL", assertThrows(Refused.class, () -> service.publish(" SECURITY-1", false, check)).code,
                "principals are compared in canonical form");
        assertEquals("COMPLIANCE_ROLE_MISSING", assertThrows(Refused.class, () -> service.publish("security-2", false, check)).code);
        assertEquals("PRINCIPAL_NOT_ELIGIBLE", assertThrows(Refused.class, () -> service.publish("root-local", false, check)).code);
        assertEquals("PRINCIPAL_NOT_ELIGIBLE", assertThrows(Refused.class, () -> service.publish("compliance-1", true, check)).code);
        assertEquals("REQUEST_CHANGED", assertThrows(Refused.class, () -> service.publish("compliance-1", false,
                new PublishRequest(rev(draft), "f".repeat(64), null))).code);
        var published = service.publish("compliance-1", false, check);
        assertEquals(1, published.get("number"));
        assertEquals("ACTIVE", store.active().orElseThrow().state());
        assertTrue(store.draft().isEmpty());
    }

    @Test void anEditorWithComplianceAdminLetsASecurityAdminPublishButNotAConfirmerOrEditor() {
        var a = create("compliance-1", null, "Z-APP", "198.51.100.0/24");
        var b = create("security-1", rev(a), "Z-DB", "203.0.113.0/25");
        var request = new PublishRequest(rev(b), (String) b.get("content_sha256"), null);
        assertEquals("SELF_APPROVAL", assertThrows(Refused.class, () -> service.publish("compliance-1", false, request)).code,
                "anyone who changed this draft since the last publish is excluded, not only the last editor");
        assertEquals(1, service.publish("security-2", false, request).get("number"));
    }

    @Test void addressesLeavingAZoneNeedARationaleAndTheOldVersionRetires() {
        var a = create("security-1", null, "Z-APP", "198.51.100.0/24");
        service.publish("compliance-1", false, new PublishRequest(rev(a), (String) a.get("content_sha256"), null));
        var b = create("security-1", null, "Z-MGMT", "198.51.100.128/28");
        var request = new PublishRequest(rev(b), (String) b.get("content_sha256"), null);
        Map<String, Object> view = service.draft(View.NAMES, "compliance-1", false, true);
        @SuppressWarnings("unchecked") var diff = (Map<String, Object>) view.get("diff");
        assertEquals(true, diff.get("rationale_required"));
        assertEquals("RATIONALE_REQUIRED", assertThrows(Refused.class, () -> service.publish("compliance-1", false, request)).code);
        var published = service.publish("compliance-1", false, new PublishRequest(rev(b), (String) b.get("content_sha256"), "split management out"));
        assertEquals(2, published.get("number"));
        assertEquals(List.of("RETIRED", "ACTIVE"), store.rows.values().stream().filter(v -> v.number() != null)
                .sorted(Comparator.comparing(ZoneMatrixRepository.Version::number)).map(ZoneMatrixRepository.Version::state).toList());
        var lookup = service.lookup("security-1", "198.51.100.130", null);
        assertEquals("Z-MGMT", lookup.get("zone_name"));
        assertEquals(1, store.access.stream().filter(k -> k.equals("ADDRESS_LOOKUP")).count());
    }

    @Test void nothingToPublishAndDiscardKeepHistory() {
        var a = create("security-1", null, "Z-APP", "198.51.100.0/24");
        service.publish("compliance-1", false, new PublishRequest(rev(a), (String) a.get("content_sha256"), null));
        var b = create("security-1", null, "Z-DB", "203.0.113.0/25");
        var draftId = store.draftId();
        var c = service.deleteZone("security-1", rev(b), (String) b.get("zone_id"));
        assertEquals("NOTHING_TO_PUBLISH", assertThrows(Refused.class, () -> service.publish("compliance-1", false,
                new PublishRequest(rev(c), (String) c.get("content_sha256"), null))).code);
        service.discardDraft("security-1", rev(c));
        assertTrue(store.draft().isEmpty());
        assertEquals("DISCARDED", store.rows.get(draftId).state());
        assertEquals(List.of("DRAFT_CREATE", "ZONE_CREATE", "ZONE_DELETE", "DRAFT_DISCARD"), store.kinds(draftId));
    }

    @Test void viewsHideBlocksUnlessPermittedAndMaskForAiview() {
        var a = create("security-1", null, "Z-APP", "198.51.100.0/24");
        service.publish("compliance-1", false, new PublishRequest(rev(a), (String) a.get("content_sha256"), null));
        String id = store.active().orElseThrow().versionId();
        var masked = service.version(id, View.MASKED, "aiview", false);
        @SuppressWarnings("unchecked") var zone = ((List<Map<String, Object>>) masked.get("zones")).get(0);
        assertEquals("ZONE-1", zone.get("name"));
        assertNull(zone.get("blocks"));
        assertNull(zone.get("description"));
        assertEquals("256", ((Map<?, ?>) zone.get("effective_addresses")).get("IPV4"));
        assertFalse(masked.toString().contains("198.51.100"));
        assertFalse(masked.toString().contains("security-1"), "actors are tokens in the masked view");
        assertTrue(store.access.isEmpty());
        var names = service.version(id, View.NAMES, "viewer-1", false);
        @SuppressWarnings("unchecked") var named = ((List<Map<String, Object>>) names.get("zones")).get(0);
        assertEquals("Z-APP", named.get("name"));
        assertNull(named.get("blocks"));
        assertTrue(store.access.isEmpty());
        var raw = service.version(id, View.BLOCKS, "security-1", true);
        @SuppressWarnings("unchecked") var rawZone = ((List<Map<String, Object>>) raw.get("zones")).get(0);
        assertEquals(List.of("198.51.100.0/24"), rawZone.get("blocks"));
        assertEquals(List.of("BLOCKS_VIEW"), store.access);
    }

    @Test void aViewIsRefusedWhenItsAuditRowCannotBeWritten() {
        var a = create("security-1", null, "Z-APP", "198.51.100.0/24");
        store.failAccess = true;
        assertEquals("ZONE_MATRIX_AUDIT_UNAVAILABLE", assertThrows(Refused.class,
                () -> service.lookup("security-1", "198.51.100.1", store.draftId())).code);
        assertEquals("INVALID_ADDRESS", assertThrows(Refused.class, () -> service.lookup("security-1", "198.51.100.0/24", null)).code);
        assertNotNull(a);
    }

    @Test void readersSeePublishedVersionsOnlyAndThePublishCheckReportsNothingToPublish() {
        var a = create("security-1", null, "Z-APP", "198.51.100.0/24");
        String draftId = store.draftId();
        var reader = service.draft(View.NAMES, "viewer-1", false, false);
        assertEquals(true, reader.get("draft_exists"));
        assertNull(reader.get("draft"));
        assertNull(reader.get("diff"));
        assertTrue(service.versions(View.NAMES, false).isEmpty());
        assertEquals(1, service.versions(View.NAMES, true).size());
        assertEquals("NOT_FOUND", assertThrows(Refused.class, () -> service.version(draftId, View.NAMES, "viewer-1", false)).code);
        service.publish("compliance-1", false, new PublishRequest(rev(a), (String) a.get("content_sha256"), null));
        assertEquals(1, service.versions(View.NAMES, false).size());
        var b = create("security-1", null, "Z-DB", "203.0.113.0/25");
        service.deleteZone("security-1", rev(b), (String) b.get("zone_id"));
        @SuppressWarnings("unchecked") var check = (Map<String, Object>) service.draft(View.NAMES, "compliance-1", false, true).get("publish_check");
        assertEquals(false, check.get("eligible"));
        assertEquals("NOTHING_TO_PUBLISH", check.get("reason"));
    }

    /** In-memory store with the same lock and revision semantics as the jOOQ repository. */
    static final class Memory implements ZoneMatrixRepository {
        final Map<String, Version> rows = new LinkedHashMap<>();
        final List<String[]> revisions = new ArrayList<>();
        final List<String> access = new ArrayList<>();
        final List<String[]> confirmations = new ArrayList<>();
        boolean failAccess;

        String draftId() { return draft().orElseThrow().versionId(); }

        List<String> kinds(String versionId) {
            return revisions.stream().filter(r -> r[0].equals(versionId)).map(r -> r[2]).toList();
        }

        private Optional<Version> state(String state) { return rows.values().stream().filter(v -> v.state().equals(state)).findFirst(); }

        @Override public List<Version> list() { return List.copyOf(rows.values()); }
        @Override public Optional<Version> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public Optional<Version> active() { return state("ACTIVE"); }
        @Override public Optional<Version> draft() { return state("DRAFT"); }
        @Override public List<Revision> revisions(String id) {
            return revisions.stream().filter(r -> r[0].equals(id)).map(r -> new Revision(Long.parseLong(r[3]), r[1], Instant.EPOCH,
                    r[2], null, "0".repeat(64), false)).toList();
        }
        @Override public void recordAccess(String actor, String kind, String versionId) {
            if (failAccess) throw new IllegalStateException("synthetic failure");
            access.add(kind);
        }

        @Override public <T> T locked(Function<DraftTransaction, T> work) {
            var snapshot = new LinkedHashMap<>(rows);
            int revisionCount = revisions.size(), confirmationCount = confirmations.size();
            try {
                return work.apply(new DraftTransaction() {
                    @Override public Optional<Version> draft() { return Memory.this.draft(); }
                    @Override public Optional<Version> active() { return Memory.this.active(); }
                    @Override public Set<String> participants(String id) {
                        Set<String> out = new LinkedHashSet<>();
                        for (String[] r : revisions) if (r[0].equals(id) && !r[2].equals("PUBLISH")) out.add(r[1]);
                        return out;
                    }
                    @Override public int nextNumber() {
                        return rows.values().stream().map(Version::number).filter(Objects::nonNull).max(Integer::compare).orElse(0) + 1;
                    }
                    @Override public void insertDraft(String id, String content, String sha, String basedOn, String actor, Instant now) {
                        rows.put(id, new Version(id, null, "DRAFT", 1, content, sha, basedOn, actor, now, actor, now, null, null));
                    }
                    @Override public void updateDraft(String id, long expected, long revision, String content, String sha, String actor, Instant now) {
                        Version v = rows.get(id);
                        if (v == null || !v.state().equals("DRAFT") || v.revision() != expected) throw new Conflict();
                        rows.put(id, new Version(id, null, "DRAFT", revision, content, sha, v.basedOnVersionId(), v.createdBy(), v.createdAt(), actor, now, null, null));
                    }
                    @Override public void appendRevision(String id, long revision, String actor, Instant now, String kind, String zoneId,
                            String sha, String content, String rationale) {
                        revisions.add(new String[]{id, actor, kind, Long.toString(revision)});
                    }
                    @Override public void recordConfirmation(String id, long revision, String source, String destination, String cellSha,
                            String actor, Instant now) {
                        if (revisions.stream().noneMatch(r -> r[0].equals(id) && r[3].equals(Long.toString(revision)))) throw new Conflict();
                        confirmations.add(new String[]{id, Long.toString(revision), source, destination, cellSha, actor});
                    }
                    @Override public void retire(String id) { change(id, "ACTIVE", "RETIRED", null, null); }
                    @Override public void activate(String id, int number, String actor, Instant now) { change(id, "DRAFT", "ACTIVE", number, actor); }
                    @Override public void discard(String id) { change(id, "DRAFT", "DISCARDED", null, null); }
                    private void change(String id, String from, String to, Integer number, String actor) {
                        Version v = rows.get(id);
                        if (v == null || !v.state().equals(from)) throw new Conflict();
                        rows.put(id, new Version(id, number != null ? number : v.number(), to, v.revision(), v.content(), v.contentSha256(),
                                v.basedOnVersionId(), v.createdBy(), v.createdAt(), v.updatedBy(), v.updatedAt(),
                                actor != null ? actor : v.publishedBy(), actor != null ? Instant.EPOCH : v.publishedAt()));
                    }
                });
            } catch (RuntimeException e) {
                rows.clear(); rows.putAll(snapshot);
                while (revisions.size() > revisionCount) revisions.remove(revisions.size() - 1);
                while (confirmations.size() > confirmationCount) confirmations.remove(confirmations.size() - 1);
                throw e;
            }
        }
    }
}
