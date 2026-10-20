package com.securityexpert.nexus.ui2.service.changes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository;
import com.securityexpert.nexus.ui2.service.policy.PolicyChangesService;

/** The merge, the cursor, the coverage notes and the aiview projection of the change feed, over synthetic rows. */
class ChangeFeedServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");
    private static final Instant FROM = NOW.minusSeconds(24 * 3600);

    /** An in-memory repository; every list is returned newest first within the asked range, like the SQL does. */
    static final class FakeRepository implements ChangeFeedRepository {
        final List<JobRow> jobs = new ArrayList<>();
        final List<PolicyInstallRow> installs = new ArrayList<>();
        final List<CrashAlertRow> alerts = new ArrayList<>();
        final List<EvidenceRow> evidence = new ArrayList<>();
        final Map<String, List<ChunkRow>> chunks = new LinkedHashMap<>();
        long cpEvidence = 0, panEvidence = 0;
        boolean fail = false;

        @Override public List<JobRow> jobs(Instant from, Instant to, Optional<String> deviceId, int limit) {
            if (fail) throw new IllegalStateException("db down");
            return jobs.stream().filter(j -> !j.submittedAt().isBefore(from) && j.submittedAt().isBefore(to))
                    .filter(j -> deviceId.isEmpty() || deviceId.get().equals(j.targetDeviceId()))
                    .sorted((a, b) -> b.submittedAt().compareTo(a.submittedAt())).limit(limit).toList();
        }
        @Override public List<PolicyInstallRow> policyInstalls(Instant from, Instant to, Optional<String> deviceId, int limit) {
            return installs.stream().filter(r -> !r.installedAt().isBefore(from) && r.installedAt().isBefore(to))
                    .filter(r -> deviceId.isEmpty() || deviceId.get().equals(r.deviceId())).limit(limit).toList();
        }
        @Override public List<CrashAlertRow> crashAlerts(Instant from, Instant to, Optional<String> deviceId, int limit) {
            return alerts.stream().filter(r -> !r.createdAt().isBefore(from) && r.createdAt().isBefore(to))
                    .filter(r -> deviceId.isEmpty() || deviceId.get().equals(r.deviceId())).limit(limit).toList();
        }
        @Override public List<EvidenceRow> evidence(Instant from, Instant to, int limit) {
            return evidence.stream().filter(r -> !r.observedAt().isBefore(from) && r.observedAt().isBefore(to)).limit(limit).toList();
        }
        @Override public long evidenceCount(String evidenceKind) {
            return "CP_SHOW_CHANGES".equals(evidenceKind) ? cpEvidence : panEvidence;
        }
        @Override public List<ChunkRow> chunks(String sourceKey, Instant from, Instant to, Optional<String> deviceId, int limit) {
            return chunks.getOrDefault(sourceKey, List.of()).stream()
                    .filter(c -> !c.collectedAt().isBefore(from) && c.collectedAt().isBefore(to))
                    .filter(c -> deviceId.isEmpty() || deviceId.get().equals(c.deviceId())).limit(limit).toList();
        }
    }

    final FakeRepository repository = new FakeRepository();
    final PolicyChangesService policy = mock(PolicyChangesService.class);
    final ChunkTextReader reader = new ChunkTextReader(mock(ArtefactStore.class));
    final ChangeFeedService service = new ChangeFeedService(repository, policy, reader, FeedFixtures::plain, FeedFixtures::masked);
    final ChangeFeedService.Permissions all = new ChangeFeedService.Permissions(true);

    static ChangeFeedService.Request request(String kinds, String cursor, int limit) {
        return new ChangeFeedService.Request(FROM.toString(), NOW.toString(), kinds, "", "", cursor, limit);
    }

    static Map<String, Object> policyRow(long revision, String changedOn, String actor) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("revision", String.valueOf(revision)); row.put("policyId", "p1"); row.put("policyName", "Synthetic policy");
        row.put("targets", List.of(Map.of("deviceId", FeedFixtures.CP_DEVICE, "name", "gw-lab-07")));
        row.put("ruleId", "r" + revision); row.put("number", 7); row.put("name", "Synthetic rule"); row.put("changedOn", changedOn);
        row.put("observedAtCollection", false); row.put("changeType", "modified"); row.put("changedBy", actor);
        row.put("changes", List.of(Map.of("field", "action", "before", "allow", "after", "deny")));
        return row;
    }

    ChangeFeedRepository.JobRow job(String id, String type, String device, String state, Instant at) {
        return new ChangeFeedRepository.JobRow(id, type, device, "device", null, "fp-1", state, state, null, at, at.plusSeconds(30));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> events(ChangeFeedResponse response) {
        return (List<Map<String, Object>>) response.body().get("events");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> coverage(ChangeFeedResponse response) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) response.body().get("coverage")) out.put((String) c.get("source"), c);
        return out;
    }

    @Test
    void mergesEverySourceNewestFirstWithDeepLinksAndSaysWhatEachSourceCovered() {
        when(policy.recent(any(), any(), anyString(), anyBoolean())).thenReturn(List.of(policyRow(3, "2026-10-17T05:00:00Z", "alice")));
        repository.jobs.add(job("j1", "cp_gateway_backup", FeedFixtures.CP_DEVICE, "COMPLETED", Instant.parse("2026-10-17T04:00:00Z")));
        repository.alerts.add(new ChangeFeedRepository.CrashAlertRow("a1", FeedFixtures.CP_DEVICE, "fwk", 1, 1760677200L, "j-log",
                Instant.parse("2026-10-17T06:10:00Z")));
        repository.installs.add(new ChangeFeedRepository.PolicyInstallRow(FeedFixtures.CP_DEVICE, "Standard", "17Oct2026 03:00:00",
                Instant.parse("2026-10-17T00:00:00Z"), "cp_policy_install_cpstat", Instant.parse("2026-10-17T20:00:00Z")));
        repository.chunks.put("pan_config", List.of(new ChangeFeedRepository.ChunkRow("ch-pan", FeedFixtures.PAN_DEVICE, "pan_config", "j-x",
                Instant.parse("2026-10-17T03:05:00Z"), 100, 1, "art-1", new byte[8], null)));
        reader.preload("ch-pan", List.of(FeedFixtures.panConfigLine("2026/10/17 05:30:00", "bob", "commit-all", null, "Succeeded", null, null)));
        repository.chunks.put("cp_cpwd_elg", List.of(new ChangeFeedRepository.ChunkRow("ch-cpwd", FeedFixtures.CP_DEVICE, "cp_cpwd_elg", "j-y",
                Instant.parse("2026-10-17T03:05:00Z"), 100, 1, "art-2", new byte[8], null)));
        reader.preload("ch-cpwd", List.of("[cpwd 1 2]@gw-lab-07[17 Oct 05:41:12] Process FWD terminated abnormally, restarting"));

        ChangeFeedResponse response = service.feed(request("", "", 50), false, all, NOW);
        assertFalse(response.masked());
        List<String> order = events(response).stream().map(e -> (String) e.get("source")).toList();
        // 06:10 crash alert, 05:30 local = 02:30Z pan commit, 05:41 local = 02:41Z cpwd, 05:00Z policy, 04:00Z job, 00:00Z install
        assertEquals(List.of("device_crash_alert", "policy_rule_history", "jobs", "cp_cpwd_elg", "pan_config", "device_policy_install"), order);
        assertEquals("", response.body().get("next_cursor"));
        Map<String, Object> policyEvent = events(response).get(1);
        assertEquals("alice", policyEvent.get("actor"));
        assertEquals("gw-lab-07", policyEvent.get("device_name"));
        assertEquals("p1", ((Map<?, ?>) policyEvent.get("links")).get("policy_id"));
        assertEquals("r3", ((Map<?, ?>) policyEvent.get("links")).get("rule_id"));
        assertEquals(List.of(Map.of("op", "-", "text", "action: \"allow\""), Map.of("op", "+", "text", "action: \"deny\"")), policyEvent.get("diff"));
        assertEquals("j1", ((Map<?, ?>) events(response).get(2).get("links")).get("job_id"));
        assertEquals("?screen=inventory&device_id=dev-cp-1&tab=logs", ((Map<?, ?>) events(response).get(0).get("links")).get("device"));
        Map<String, Map<String, Object>> coverage = coverage(response);
        assertEquals(Set.of("policy_rule_history", "pan_config", "device_policy_install", "policy_change_evidence", "jobs", "cp_cpwd_elg",
                "device_crash_alert"), coverage.keySet());
        assertEquals("OK", coverage.get("pan_config").get("state"));
        assertEquals("OFF", coverage.get("policy_change_evidence").get("state"));
        assertTrue(((String) coverage.get("policy_change_evidence").get("note")).contains("V163 reader is off by default"));
        assertEquals(ChangeFeedService.ORDERING_NOTE, response.body().get("ordering_note"));
    }

    @Test
    void pagesWithTheCursorWithoutDuplicatesOrLossEvenWhenTimesTie() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) rows.add(policyRow(100 + i, "2026-10-17T05:00:00Z", "alice")); // seven revisions at one instant
        rows.add(policyRow(90, "2026-10-17T04:00:00Z", "alice"));
        when(policy.recent(any(), any(), anyString(), anyBoolean())).thenReturn(rows);
        for (int i = 0; i < 4; i++) {
            repository.jobs.add(job("j" + i, "cp_gateway_backup", FeedFixtures.CP_DEVICE, "COMPLETED", Instant.parse("2026-10-17T05:00:00Z")));
        }
        Set<String> seen = new HashSet<>();
        String cursor = "";
        int pages = 0;
        do {
            ChangeFeedResponse page = service.feed(request("", cursor, 3), false, all, NOW);
            for (Map<String, Object> e : events(page)) assertTrue(seen.add((String) e.get("key")), "duplicate " + e.get("key"));
            cursor = (String) page.body().get("next_cursor");
            pages++;
            assertTrue(pages < 20, "paging must end");
        } while (!cursor.isEmpty());
        // 7 policy rows + 1 older + 1 folded job line (4 identical jobs in one hour)
        assertEquals(9, seen.size());
        assertTrue(seen.stream().anyMatch(k -> k.startsWith("jobs:cp_gateway_backup:")), "jobs are folded");
    }

    @Test
    void kindsVendorAndDeviceNarrowTheFeedAndASourceOutsideTheVendorIsNotRead() {
        when(policy.recent(any(), any(), anyString(), anyBoolean())).thenReturn(List.of(policyRow(3, "2026-10-17T05:00:00Z", "alice")));
        repository.jobs.add(job("j1", "pan_device_state_backup", FeedFixtures.PAN_DEVICE, "FAILED", Instant.parse("2026-10-17T04:00:00Z")));
        ChangeFeedResponse jobsOnly = service.feed(request("NEXUS_JOB", "", 50), false, all, NOW);
        assertEquals(List.of("jobs"), events(jobsOnly).stream().map(e -> e.get("source")).toList());
        assertEquals(Set.of("jobs"), coverage(jobsOnly).keySet());
        assertEquals("BAD", events(jobsOnly).get(0).get("tone"));
        ChangeFeedResponse pan = service.feed(new ChangeFeedService.Request(FROM.toString(), NOW.toString(), "", "palo_alto", "", "", 50), false, all, NOW);
        assertEquals(List.of("jobs"), events(pan).stream().map(e -> e.get("source")).toList());
        assertEquals("not read: vendor filter", coverage(pan).get("cp_cpwd_elg").get("note"));
        ChangeFeedResponse device = service.feed(new ChangeFeedService.Request(FROM.toString(), NOW.toString(), "", "", FeedFixtures.CP_DEVICE, "", 50), false, all, NOW);
        assertEquals(List.of("policy_rule_history"), events(device).stream().map(e -> e.get("source")).toList());
    }

    @Test
    void aFailingSourceIsUnavailableNotZeroAndAMissingPermissionIsSaid() {
        when(policy.recent(any(), any(), anyString(), anyBoolean())).thenReturn(List.of());
        repository.fail = true;
        ChangeFeedResponse response = service.feed(request("", "", 50), false, new ChangeFeedService.Permissions(false), NOW);
        assertEquals("UNAVAILABLE", coverage(response).get("jobs").get("state"));
        assertTrue(((String) coverage(response).get("jobs").get("note")).contains("IllegalStateException"));
        assertEquals("NOT_PERMITTED", coverage(response).get("pan_config").get("state"));
        assertEquals("NOT_PERMITTED", coverage(response).get("cp_cpwd_elg").get("state"));
        assertTrue(events(response).isEmpty());
    }

    @Test
    void withoutAnArtefactStoreTheStoredLogSourcesAreUnavailable() {
        when(policy.recent(any(), any(), anyString(), anyBoolean())).thenReturn(List.of());
        ChangeFeedService noStore = new ChangeFeedService(repository, policy, new ChunkTextReader(null), FeedFixtures::plain, FeedFixtures::masked);
        ChangeFeedResponse response = noStore.feed(request("", "", 50), false, all, NOW);
        assertEquals("UNAVAILABLE", coverage(response).get("pan_config").get("state"));
        assertTrue(((String) coverage(response).get("pan_config").get("note")).contains("SYSTEM_LOG_STORE_UNAVAILABLE"));
    }

    @Test
    void theAiviewProjectionShowsTokensAndPseudonymsNeverANameAndFailsClosedWithoutMasking() {
        when(policy.recent(any(), any(), anyString(), anyBoolean())).thenAnswer(call -> {
            // PolicyChangesService projects its own rows (PolicyPrivacy) when asked for the masked view.
            boolean masked = call.getArgument(3);
            Map<String, Object> row = policyRow(3, "2026-10-17T05:00:00Z", masked ? "masked-actor-token" : "alice");
            if (masked) row.put("targets", List.of(Map.of("deviceId", FeedFixtures.CP_DEVICE, "name", "FW-SYN-01")));
            return List.of(row);
        });
        repository.jobs.add(new ChangeFeedRepository.JobRow("j1", "cp_gateway_backup", FeedFixtures.CP_DEVICE, "device", null, "fp", "FAILED",
                "FAILED", "sftp fetch failed: gw-lab-07 (192.0.2.9)", Instant.parse("2026-10-17T04:00:00Z"), null));
        repository.chunks.put("pan_config", List.of(new ChangeFeedRepository.ChunkRow("ch-pan", FeedFixtures.PAN_DEVICE, "pan_config", "j-x",
                Instant.parse("2026-10-17T03:05:00Z"), 100, 1, "art-1", new byte[8], null)));
        reader.preload("ch-pan", List.of(FeedFixtures.panConfigLine("2026/10/17 05:30:00", "bob", "edit", " vsys  vsys1 address  a1", "Submitted", null, null)));

        ChangeFeedResponse masked = service.feed(request("", "", 50), true, all, NOW);
        assertTrue(masked.masked());
        String json = masked.body().toString();
        assertFalse(json.contains("alice") || json.contains("bob") || json.contains("gw-lab-07") || json.contains("192.0.2.9") || json.contains("edge-fw"), json);
        assertTrue(json.contains("USR-"), "the PAN admin is a keyed user token");
        assertTrue(json.contains("masked-actor-token"), "the policy row is asked for masked");

        ChangeFeedService closed = new ChangeFeedService(repository, policy, reader, FeedFixtures::plain, FeedFixtures::maskedWithoutText);
        ChangeFeedResponse unavailable = closed.feed(request("", "", 50), true, all, NOW);
        assertEquals("UNAVAILABLE", coverage(unavailable).get("pan_config").get("state"));
        assertFalse(unavailable.body().toString().contains("bob"));
        // A failed job's reason is free text: it is UNKNOWN-safe (the source fails closed) rather than shown raw.
        assertEquals("UNAVAILABLE", coverage(unavailable).get("jobs").get("state"));
    }

    @Test
    void refusesABadRangeKindVendorCursorOrLimit() {
        assertEquals("RANGE_INVALID", refusal(new ChangeFeedService.Request(NOW.toString(), FROM.toString(), "", "", "", "", 10)));
        assertEquals("RANGE_TOO_LONG", refusal(new ChangeFeedService.Request(NOW.minusSeconds(40L * 86400).toString(), NOW.toString(), "", "", "", "", 10)));
        assertEquals("KIND_INVALID", refusal(request("USER,SOMETHING", "", 10)));
        assertEquals("VENDOR_INVALID", refusal(new ChangeFeedService.Request("", "", "", "cisco", "", "", 10)));
        assertEquals("CURSOR_INVALID", refusal(request("", "not-a-cursor", 10)));
        assertEquals("LIMIT_INVALID", refusal(request("", "", 999)));
        assertEquals("TIME_INVALID", refusal(new ChangeFeedService.Request("yesterday", "", "", "", "", "", 10)));
        assertEquals("DEVICE_ID_INVALID", refusal(new ChangeFeedService.Request("", "", "", "", "a b", "", 10)));
    }

    private String refusal(ChangeFeedService.Request request) {
        var refused = assertThrows(ChangeFeedService.RefusedException.class, () -> service.window(request, NOW));
        assertNotNull(refused.refusal.message());
        assertEquals(400, refused.refusal.status());
        return refused.refusal.code();
    }

    @Test
    void defaultsToTheLast24HoursAndFiftyEvents() {
        ChangeFeedService.Window w = service.window(new ChangeFeedService.Request("", "", "", "", "", "", 0), NOW);
        assertEquals(FROM, w.from());
        assertEquals(NOW, w.to());
        assertEquals(50, w.limit());
        assertEquals(3, w.kinds().size());
    }
}
