package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.Edit;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.Link;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.Pending;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeActorRepository.RuleIdentity;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/**
 * The join of design section 4.2 against stored rows only: synthetic names, a zero key, no database, no device. Device time
 * is Europe/Istanbul (UTC+3 in November), so the collection at 09:00Z is 12:00:00 device time.
 */
class PolicyChangeActorResolverTest {
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Instant COLLECTED = Instant.parse("2026-11-12T09:00:00Z");   // 12:00:00 device
    private static final Instant PREVIOUS = Instant.parse("2026-11-12T03:00:00Z");   // 06:00:00 device
    private static final HostnameFingerprint KEY = HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
    private static final String REF = PanRuleRef.hmac(KEY, PanRuleRef.fromSnapshot("DG-EXAMPLE", "Pre rules", "Rule-EXAMPLE").orElseThrow());
    private static final String OTHER_REF = PanRuleRef.hmac(KEY, PanRuleRef.fromSnapshot("DG-EXAMPLE", "Pre rules", "Rule-OTHER").orElseThrow());

    private PolicyChangeActorRepository repository;
    private PolicyChangeActorResolver resolver;

    @BeforeEach void setUp() {
        repository = mock(PolicyChangeActorRepository.class);
        resolver = new PolicyChangeActorResolver(repository, KEY);
        when(repository.identities(eq("p1"), any())).thenReturn(Map.of("uuid:r1", new RuleIdentity("DG-EXAMPLE", "Pre rules", "Rule-EXAMPLE")));
        when(repository.previousObservation(eq("src"), eq("c1"), eq(COLLECTED))).thenReturn(Optional.of(PREVIOUS));
        // A commit at 11:50 inside (P - 5 min, C + 5 min], and an earlier commit the day before.
        when(repository.commitTimes("src", "2026-11-12 05:55:00", "2026-11-12 12:05:00")).thenReturn(List.of("2026-11-12 11:50:00"));
        when(repository.commitBefore("src", "2026-11-12 11:50:00")).thenReturn(Optional.of("2026-11-11 17:00:00"));
    }

    private static Pending row(String type) { return new Pending(7, "p1", "uuid:r1", type, COLLECTED, "c1", null); }

    private static Edit edit(String id, String ref, String adminRef, String name, String cmd) {
        return new Edit(id, ref, adminRef, name, cmd, "2026-11-12 10:00:00");
    }

    private Link only(String type) {
        when(repository.pending(eq("src"), any(), anyInt())).thenReturn(List.of(row(type)));
        resolver.resolve("src", "job-1", NOW);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Link>> links = ArgumentCaptor.forClass(List.class);
        verify(repository).saveLinks(eq("job-1"), links.capture());
        assertEquals(1, links.getValue().size());
        return links.getValue().get(0);
    }

    /** A row whose answer depends on evidence the log has not (yet) given: no link is written, the row stays pending. */
    private PolicyChangeActorResolver.Result deferred(String type, String reason) {
        when(repository.pending(eq("src"), any(), anyInt())).thenReturn(List.of(row(type)));
        var result = resolver.resolve("src", "job-1", NOW);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Link>> links = ArgumentCaptor.forClass(List.class);
        verify(repository).saveLinks(eq("job-1"), links.capture());
        assertTrue(links.getValue().isEmpty(), "an evidence gap is never persisted as the row's answer");
        assertEquals(1, result.deferred());
        assertEquals(0, result.unknown());
        assertEquals(Map.of(reason, 1), result.reasons());
        assertTrue(result.note().contains("deferred=1"));
        return result;
    }

    @Test void oneAdministratorBeforeTheCommitIsMatchedEvenWithSeveralEntries() {
        when(repository.edits("src", "2026-11-11 17:00:00", "2026-11-12 11:50:00")).thenReturn(List.of(
                edit("e1", REF, "ref-a", "admin-a", "edit"), edit("e2", REF, "ref-a", "admin-a", "set"), edit("e3", OTHER_REF, "ref-b", "admin-b", "edit")));
        Link link = only("modified");
        assertEquals("MATCHED", link.state());
        assertEquals(List.of("admin-a"), link.names());
        assertNull(link.reason());
    }

    @Test void twoAdministratorsOnTheSameRuleAreAmbiguousNeverPickedBetween() {
        when(repository.edits("src", "2026-11-11 17:00:00", "2026-11-12 11:50:00")).thenReturn(List.of(
                edit("e1", REF, "ref-a", "admin-a", "edit"), edit("e2", REF, "ref-b", "admin-b", "edit")));
        Link link = only("modified");
        assertEquals("AMBIGUOUS", link.state());
        assertEquals(List.of("admin-a", "admin-b"), link.names());
    }

    @Test void noEntryForTheRuleIsUnknownAndACommandThatCannotHaveCausedTheChangeIsNotACandidate() {
        when(repository.edits("src", "2026-11-11 17:00:00", "2026-11-12 11:50:00")).thenReturn(List.of(
                edit("e1", OTHER_REF, "ref-b", "admin-b", "edit"), edit("e2", REF, "ref-c", "admin-c", "delete")));
        Link link = only("modified");
        assertEquals("UNKNOWN", link.state());
        assertEquals("NO_ENTRY", link.reason());
        assertTrue(link.names().isEmpty());
    }

    @Test void aRemovedRuleOnlyMatchesADelete() {
        when(repository.identities(eq("p1"), any())).thenReturn(Map.of());
        when(repository.edits("src", "2026-11-11 17:00:00", "2026-11-12 11:50:00")).thenReturn(List.of(
                edit("e1", REF, "ref-a", "admin-a", "edit"), edit("e2", REF, "ref-b", "admin-b", "delete")));
        when(repository.pending(eq("src"), any(), anyInt())).thenReturn(List.of(new Pending(7, "p1", "uuid:r1", "removed", COLLECTED, "c1",
                "[{\"field\":\"name\",\"before\":\"Rule-EXAMPLE\",\"after\":null},{\"field\":\"section\",\"before\":\"Pre rules\",\"after\":null},"
                + "{\"field\":\"container\",\"before\":\"DG-EXAMPLE\",\"after\":null}]")));
        resolver.resolve("src", "job-1", NOW);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Link>> links = ArgumentCaptor.forClass(List.class);
        verify(repository).saveLinks(eq("job-1"), links.capture());
        assertEquals("MATCHED", links.getValue().get(0).state());
        assertEquals(List.of("admin-b"), links.getValue().get(0).names());
    }

    @Test void noCommitInTheCollectionWindowLeavesTheRowPendingNotUnknown() {
        when(repository.commitTimes("src", "2026-11-12 05:55:00", "2026-11-12 12:05:00")).thenReturn(List.of());
        deferred("modified", "NO_COMMIT_SEEN");
        verify(repository, never()).edits(any(), any(), any());
    }

    @Test void aWindowWithoutAnEarlierStoredCommitIsNotCoveredAndNeverNamesAnyoneButStaysPending() {
        when(repository.commitBefore("src", "2026-11-12 11:50:00")).thenReturn(Optional.empty());
        deferred("modified", "WINDOW_NOT_COVERED");
        verify(repository, never()).edits(any(), any(), any());
    }

    @Test void aWindowLongerThanThirtyDaysIsNotCoveredAndStaysPending() {
        when(repository.commitBefore("src", "2026-11-12 11:50:00")).thenReturn(Optional.of("2026-10-01 08:00:00"));
        deferred("modified", "WINDOW_NOT_COVERED");
    }

    @Test void withoutAPreviousCollectionTheRowStaysPending() {
        when(repository.previousObservation(any(), any(), any())).thenReturn(Optional.empty());
        deferred("modified", "PREVIOUS_COLLECTION_UNKNOWN");
    }

    @Test void aLaterRunLinksARowThatWasDeferredOnceTheEvidenceArrives() {
        when(repository.pending(eq("src"), any(), anyInt())).thenReturn(List.of(row("modified")));
        when(repository.commitTimes("src", "2026-11-12 05:55:00", "2026-11-12 12:05:00")).thenReturn(List.of());
        resolver.resolve("src", "job-1", NOW);
        when(repository.commitTimes("src", "2026-11-12 05:55:00", "2026-11-12 12:05:00")).thenReturn(List.of("2026-11-12 11:50:00"));
        when(repository.edits("src", "2026-11-11 17:00:00", "2026-11-12 11:50:00")).thenReturn(List.of(edit("e1", REF, "ref-a", "admin-a", "edit")));
        resolver.resolve("src", "job-2", NOW);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Link>> links = ArgumentCaptor.forClass(List.class);
        verify(repository).saveLinks(eq("job-1"), links.capture());
        assertTrue(links.getValue().isEmpty());
        verify(repository).saveLinks(eq("job-2"), links.capture());
        assertEquals("MATCHED", links.getValue().get(0).state());
    }

    @Test void aRuleInALocalOrDefaultSectionHasNoReference() {
        when(repository.identities(eq("p1"), any())).thenReturn(Map.of("uuid:r1", new RuleIdentity("DG-EXAMPLE", "Local rules", "Rule-EXAMPLE")));
        assertEquals("RULE_REF_UNAVAILABLE", only("modified").reason());
        verify(repository, never()).commitTimes(any(), any(), any());
    }

    @Test void withoutTheProductKeyNothingIsLinkedToAName() {
        resolver = new PolicyChangeActorResolver(repository, null);
        assertEquals("RULE_REF_UNAVAILABLE", only("modified").reason());
    }

    @Test void theRunCountsOnlyAndClearsNamesOlderThanThirtyDays() {
        when(repository.pending(eq("src"), any(), anyInt())).thenReturn(List.of(row("modified")));
        when(repository.edits("src", "2026-11-11 17:00:00", "2026-11-12 11:50:00")).thenReturn(List.of(edit("e1", REF, "ref-a", "admin-a", "edit")));
        when(repository.clearExpiredNames("src", "2026-10-13 13:00:00")).thenReturn(3);
        var result = resolver.resolve("src", "job-1", NOW);
        assertEquals(1, result.examined());
        assertEquals(1, result.matched());
        assertEquals(3, result.namesCleared());
        assertFalse(result.note().contains("admin-a"), "the transcript note carries counts, never a name");
        assertTrue(result.note().startsWith("PAN_ACTOR_LINK examined=1 matched=1"));
    }

    @Test void aRemovedRuleWhoseDiffLacksTheIdentityFieldsHasNoReference() {
        assertNull(PolicyChangeActorResolver.fromRemovedChanges("[{\"field\":\"name\",\"before\":\"Rule-EXAMPLE\"}]"));
        assertNull(PolicyChangeActorResolver.fromRemovedChanges("not json"));
        assertNull(PolicyChangeActorResolver.fromRemovedChanges(null));
    }
}
