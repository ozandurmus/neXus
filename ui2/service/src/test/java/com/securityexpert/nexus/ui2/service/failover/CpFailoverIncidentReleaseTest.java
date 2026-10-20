package com.securityexpert.nexus.ui2.service.failover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** PO decision 2026-10-20 item 1: who may release a failover incident, and what the service passes on. */
class CpFailoverIncidentReleaseTest {
    private static final String CLUSTER = "CLS-TEST-01";
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
    private final JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final ManagementEndpointSshTrustRepository trust = mock(ManagementEndpointSshTrustRepository.class);
    private final CpFailoverService service = new CpFailoverService(devices, inventory, store, rbac, trust,
        new FailoverMutationSwitch(true), JobWindowPolicy.SYSTEM);

    private void roles(String actor, Set<String> roles) {
        when(rbac.evaluate(eq(actor), any(), any())).thenAnswer(call -> {
            Optional<String> role = call.getArgument(1);
            return new RbacEvaluator.Decision(role.filter(roles::contains).isPresent()
                ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                Optional.of("synthetic-authority"), Optional.empty(), Optional.empty());
        });
    }

    @Test void onlySecurityAdminMayReleaseAndTheSessionActorAndReasonReachTheStore() {
        for (String role : List.of(RoleToken.OPERATION_ADMIN, RoleToken.OPERATOR, RoleToken.VIEWER, RoleToken.REPLAY_VIEWER)) {
            String actor = "synthetic-" + role;
            roles(actor, Set.of(role));
            assertFalse(service.mayReleaseIncident(actor), role);
            assertEquals("WRONG_ROLE", assertThrows(CpFailoverService.Refusal.class,
                () -> service.releaseIncident("run-a", "Reviewed out of band", actor)).code(), role);
        }
        // aiview holds viewer and replay_viewer; even a security_admin binding beside replay_viewer is refused.
        roles("synthetic-aiview", Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER));
        assertFalse(service.mayReleaseIncident("synthetic-aiview"));
        assertFalse(service.mayReleaseIncident(null));
        verifyNoInteractions(store);

        roles("synthetic-admin", Set.of(RoleToken.SECURITY_ADMIN));
        assertTrue(service.mayReleaseIncident("synthetic-admin"));
        when(store.releaseIncident("run-a", "synthetic-admin", "Former active returned out of band"))
            .thenReturn(JooqCpFailoverRepository.INCIDENT_RELEASED);
        assertEquals(JooqCpFailoverRepository.INCIDENT_RELEASED,
            service.releaseIncident("run-a", "  Former active returned out of band  ", "synthetic-admin"));
        verify(store).releaseIncident("run-a", "synthetic-admin", "Former active returned out of band");
    }

    @Test void reasonIsMandatoryAndStoreRefusalsReachTheCaller() {
        roles("synthetic-admin", Set.of(RoleToken.SECURITY_ADMIN));
        for (String reason : new String[]{null, "", "   ", "x".repeat(CpFailoverService.RELEASE_REASON_MAX + 1)})
            assertEquals("RELEASE_REASON_REQUIRED", assertThrows(CpFailoverService.Refusal.class,
                () -> service.releaseIncident("run-a", reason, "synthetic-admin")).code());
        verifyNoInteractions(store);
        for (String code : List.of(JooqCpFailoverRepository.INCIDENT_NOT_FOUND,
                JooqCpFailoverRepository.INCIDENT_RUN_NOT_TERMINAL, JooqCpFailoverRepository.INCIDENT_RUN_STILL_OWNED)) {
            when(store.releaseIncident("run-" + code, "synthetic-admin", "Reviewed")).thenReturn(code);
            assertEquals(code, assertThrows(CpFailoverService.Refusal.class,
                () -> service.releaseIncident("run-" + code, "Reviewed", "synthetic-admin")).code());
        }
    }

    @Test void everyReaderSeesIncidentsWithTheOpaqueUnitIdsOfTheScreens() {
        roles("synthetic-operator", Set.of(RoleToken.OPERATOR));
        var plain = new JooqCpFailoverRepository.Incident(CLUSTER, "run-a", null, "check_point", "MUTATION_STOPPED",
            Instant.EPOCH, List.of("member-a", "member-b"), List.of(), true);
        var vs = new JooqCpFailoverRepository.Incident(CLUSTER, "run-b", "12", "check_point", "UNRESOLVED_DISPATCH",
            Instant.EPOCH, List.of("member-a", "member-b"), List.of("dispatch-a"), false);
        when(store.openIncidents()).thenReturn(List.of(plain, vs));
        var views = service.incidents("synthetic-operator");
        String clusterId = UUID.nameUUIDFromBytes(CLUSTER.getBytes(StandardCharsets.UTF_8)).toString();
        assertEquals(clusterId, views.get(0).clusterId());
        assertEquals(clusterId, views.get(0).unitId());
        assertEquals(UUID.nameUUIDFromBytes((CLUSTER + ":12").getBytes(StandardCharsets.UTF_8)).toString(),
            views.get(1).unitId());
        assertFalse(views.get(0).incident().blocksEstate());
        assertTrue(views.get(1).incident().blocksEstate());
        roles("synthetic-nobody", Set.of());
        assertEquals("WRONG_ROLE", assertThrows(CpFailoverService.Refusal.class,
            () -> service.incidents("synthetic-nobody")).code());
    }
}
