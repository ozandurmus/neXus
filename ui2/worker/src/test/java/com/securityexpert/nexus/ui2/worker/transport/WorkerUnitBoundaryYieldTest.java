package com.securityexpert.nexus.ui2.worker.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository.Owner;

/**
 * Deploy-freeze Phase 1: long read-only jobs on the general worker yield to a drain at a unit boundary; writes,
 * failover, backups, diagnostics and script runs never do.
 */
class WorkerUnitBoundaryYieldTest {
    private final EndpointAdmissionRepository repository = mock(EndpointAdmissionRepository.class);

    private static Owner owner(String purpose) {
        return new Owner("job-1", 3, null, 0, "general", "general-pod-a", 1, purpose, false);
    }

    @AfterEach void uninstall() { EndpointRuntime.uninstallForTest(); }

    @ParameterizedTest
    @ValueSource(strings = {"INVENTORY", "CONFIGURATION", "DISCOVERY"})
    void readOnlyPurposesYieldOnlyWhileDraining(String purpose) {
        EndpointRuntime.install(repository);
        try (var scope = EndpointRuntime.open(owner(purpose))) {
            when(repository.openingAllowed(any())).thenReturn(true);
            assertDoesNotThrow(EndpointRuntime::yieldAtUnitBoundary);
            when(repository.openingAllowed(any())).thenReturn(false);
            assertThrows(EndpointRuntime.AdmissionWait.class, EndpointRuntime::yieldAtUnitBoundary);
            // An unreadable owner row lets the read continue: the yield is an optimisation, never a new failure.
            when(repository.openingAllowed(any())).thenThrow(new IllegalStateException("ADMISSION_DB_UNAVAILABLE"));
            assertDoesNotThrow(EndpointRuntime::yieldAtUnitBoundary);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BACKUP", "FAILOVER_EXECUTION", "FAILOVER_READINESS", "CONFIGURATION_WRITE", "DIAGNOSTIC",
        "ENROLLMENT", "POLICY"})
    void writesFailoverDiagnosticsAndPolicyNeverYieldAtAUnitBoundary(String purpose) {
        EndpointRuntime.install(repository);
        when(repository.openingAllowed(any())).thenReturn(false);
        try (var scope = EndpointRuntime.open(owner(purpose))) {
            assertDoesNotThrow(EndpointRuntime::yieldAtUnitBoundary);
        }
        verify(repository, never()).openingAllowed(any());
    }

    @Test void withoutAJobScopeTheCheckIsANoOp() {
        EndpointRuntime.install(repository);
        assertDoesNotThrow(EndpointRuntime::yieldAtUnitBoundary);
        verifyNoInteractions(repository);
    }

    /** Pins the call sites to read-only collectors; a backup, failover, script or support-bundle path must not appear. */
    @Test void unitBoundaryYieldIsCalledOnlyFromReadOnlyCollectors() throws IOException {
        Path root = Path.of("src/main/java/com/securityexpert/nexus/ui2/worker");
        Set<String> callers;
        try (var files = Files.walk(root)) {
            callers = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                // Any mention (call, method reference, spacing variant) outside the definition counts as a call site.
                try { return !path.endsWith("transport/EndpointRuntime.java") && Files.readString(path).contains("yieldAtUnitBoundary"); }
                catch (IOException unreadable) { throw new IllegalStateException(unreadable); }
            }).map(path -> root.relativize(path).toString().replace('\\', '/')).collect(Collectors.toSet());
        }
        assertEquals(Set.of(
            "inventory/InventoryCapabilityExecutor.java",
            "configuration/ConfigurationCapabilityExecutor.java",
            "discovery/cp/MgmtCliEnumerationAdapter.java",
            "discovery/cp/ManagementPlaneEnumerationAdapter.java"), callers);
    }
}
