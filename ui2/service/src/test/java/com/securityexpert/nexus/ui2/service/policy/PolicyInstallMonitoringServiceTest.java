package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.policy.PolicyInstallMonitoringService.*;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PolicyInstallMonitoringServiceTest {

    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);

    @Test
    void checkPointOutOfDateIsComputedPerPackageAndOtherPackagePublishDoesNotFlagUnchangedPackage() {
        Instant t10 = Instant.parse("2026-11-17T10:00:00Z");
        Instant t12 = Instant.parse("2026-11-17T12:00:00Z");
        Instant t18 = Instant.parse("2026-11-17T18:00:00Z");
        Instant t22 = Instant.parse("2026-11-17T22:00:00Z");

        // Package A and Package B live in the SAME Check Point MDS domain ("dom-1").
        // Package A was last changed at 10:00 and installed on dev-cp-a at 12:00.
        // Package B was changed/published at 22:00 (advancing the domain's last-published session to 22:00),
        // while dev-cp-b still has the 18:00 install of Package B.
        PolicySnapshot.Metadata pkgA = new PolicySnapshot.Metadata(
                "pkg-a", "mds-1", "MDS-1", "CP", "dom-1", "Domain-One", "Package-Alpha",
                t22.toString(), "art-a", List.of(new PolicySnapshot.Target("dev-cp-a", "gw-alpha", "0", "INSTALLED"))
        );
        PolicySnapshot.Metadata pkgB = new PolicySnapshot.Metadata(
                "pkg-b", "mds-1", "MDS-1", "CP", "dom-1", "Domain-One", "Package-Bravo",
                t22.toString(), "art-b", List.of(new PolicySnapshot.Target("dev-cp-b", "gw-bravo", "0", "INSTALLED"))
        );

        Map<String, Instant> packageLastChanges = Map.of(
                "pkg-a", t10,
                "pkg-b", t22
        );
        Map<String, DevicePolicyInstall> installs = Map.of(
                "dev-cp-a", new DevicePolicyInstall("dev-cp-a", Optional.of("Package-Alpha"), Optional.of("17Nov2026 12:00:00"), Optional.of(t12), "cp_policy_install_cpstat", Optional.of(t22)),
                "dev-cp-b", new DevicePolicyInstall("dev-cp-b", Optional.of("Package-Bravo"), Optional.of("17Nov2026 18:00:00"), Optional.of(t18), "cp_policy_install_cpstat", Optional.of(t22))
        );

        StoredEvidence evidence = new StoredEvidence(
                List.of(pkgA, pkgB),
                Map.of(),
                packageLastChanges,
                installs,
                List.of(),
                List.of()
        );

        PolicyInstallMonitoringService service = new PolicyInstallMonitoringService(() -> evidence, names);
        Map<String, Object> result = service.status(new Request("", "", "", 0), false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertEquals(2, rows.size());

        Map<String, Object> rowA = rows.stream().filter(r -> "pkg-a".equals(r.get("package_id"))).findFirst().orElseThrow();
        Map<String, Object> rowB = rows.stream().filter(r -> "pkg-b".equals(r.get("package_id"))).findFirst().orElseThrow();

        // Package A MUST remain INSTALLED despite Package B publishing later in the same domain.
        assertEquals(PolicyInstallMonitoringService.STATE_INSTALLED, rowA.get("state"));
        // Package B MUST be OUT_OF_DATE because its own last_change_at (22:00) > installed_at (18:00).
        assertEquals(PolicyInstallMonitoringService.STATE_OUT_OF_DATE, rowB.get("state"));
    }

    @Test
    void managementOnlyNeverProducesInstalledForCheckPointOrPanorama() {
        // Check Point: management claims installed=true, but direct gateway cpstat read is missing.
        DerivedStatus cpMgmtOnly = PolicyInstallMonitoringService.deriveCheckPointState(
                "Standard",
                "0",
                Boolean.TRUE,
                null,
                Instant.parse("2026-11-17T10:00:00Z")
        );
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, cpMgmtOnly.state());
        assertEquals(PolicyInstallMonitoringService.REASON_MANAGEMENT_ONLY, cpMgmtOnly.reason());

        // Panorama: device group reports IN_SYNC, but direct firewall commit job timestamp is missing.
        DerivedStatus panMgmtOnly = PolicyInstallMonitoringService.derivePaloAltoState(
                "DG-Core",
                "IN_SYNC",
                true,
                null,
                Instant.parse("2026-11-17T10:00:00Z")
        );
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, panMgmtOnly.state());
        assertEquals(PolicyInstallMonitoringService.REASON_MANAGEMENT_ONLY, panMgmtOnly.reason());

        // Panorama: device group reports IN_SYNC and direct firewall commit timestamp is after DG change -> INSTALLED.
        DevicePolicyInstall panDirect = new DevicePolicyInstall(
                "dev-pan-1",
                Optional.empty(),
                Optional.of("2026/11/17 15:00:00"),
                Optional.of(Instant.parse("2026-11-17T15:00:00Z")),
                "pan_show_jobs_all",
                Optional.of(Instant.parse("2026-11-17T15:05:00Z"))
        );
        DerivedStatus panInstalled = PolicyInstallMonitoringService.derivePaloAltoState(
                "DG-Core",
                "IN_SYNC",
                true,
                panDirect,
                Instant.parse("2026-11-17T12:00:00Z")
        );
        assertEquals(PolicyInstallMonitoringService.STATE_INSTALLED, panInstalled.state());
    }

    @Test
    void checkPointNotInstalledWhenGatewayReportsDifferentOrEmptyPolicyName() {
        Instant t12 = Instant.parse("2026-11-17T12:00:00Z");
        DevicePolicyInstall differentPolicy = new DevicePolicyInstall(
                "dev-cp-1",
                Optional.of("Legacy-Policy"),
                Optional.of("17Nov2026 12:00:00"),
                Optional.of(t12),
                "cp_policy_install_cpstat",
                Optional.of(t12)
        );
        DerivedStatus mismatch = PolicyInstallMonitoringService.deriveCheckPointState(
                "Standard-Policy", "0", Boolean.TRUE, differentPolicy, t12
        );
        assertEquals(PolicyInstallMonitoringService.STATE_NOT_INSTALLED, mismatch.state());

        DevicePolicyInstall emptyPolicy = new DevicePolicyInstall(
                "dev-cp-1",
                Optional.of("-"),
                Optional.empty(),
                Optional.empty(),
                "cp_policy_install_cpstat",
                Optional.of(t12)
        );
        DerivedStatus noneInstalled = PolicyInstallMonitoringService.deriveCheckPointState(
                "Standard-Policy", "0", Boolean.FALSE, emptyPolicy, t12
        );
        assertEquals(PolicyInstallMonitoringService.STATE_NOT_INSTALLED, noneInstalled.state());
    }

    @Test
    void ciscoAsaShowsSavedOrUnsavedFromTheNormalisedRelationNeverInstalled() {
        DerivedStatus saved = PolicyInstallMonitoringService.deriveCiscoAsaState("MATCH", false);
        assertEquals(PolicyInstallMonitoringService.STATE_SAVED, saved.state());
        assertNotEquals(PolicyInstallMonitoringService.STATE_INSTALLED, saved.state());

        DerivedStatus unsaved = PolicyInstallMonitoringService.deriveCiscoAsaState("MISMATCH", false);
        assertEquals(PolicyInstallMonitoringService.STATE_UNSAVED, unsaved.state());

        DerivedStatus matchButChangedSince = PolicyInstallMonitoringService.deriveCiscoAsaState("MATCH", true);
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, matchButChangedSince.state());
        assertTrue(matchButChangedSince.reason().contains("changed after the last backup"));

        DerivedStatus mismatchButChangedSince = PolicyInstallMonitoringService.deriveCiscoAsaState("MISMATCH", true);
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, mismatchButChangedSince.state());

        DerivedStatus notEvaluable = PolicyInstallMonitoringService.deriveCiscoAsaState("NOT_EVALUABLE", false);
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, notEvaluable.state());
        assertTrue(notEvaluable.reason().contains("not possible"));

        for (String none : new String[] {null, "", "  "}) {
            DerivedStatus unmeasured = PolicyInstallMonitoringService.deriveCiscoAsaState(none, false);
            assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, unmeasured.state());
            assertTrue(unmeasured.reason().contains("not measured"));
        }
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN,
                PolicyInstallMonitoringService.deriveCiscoAsaState("SOMETHING_ELSE", false).state());
    }

    @Test
    void fortiGateDirectShowsNotApplicableWithHaParityAndFortiManagerStaysUnknown() {
        DerivedStatus fgtEqual = PolicyInstallMonitoringService.deriveFortiGateDirectState("PASS", "MATCH", null);
        assertEquals(PolicyInstallMonitoringService.STATE_NOT_APPLICABLE, fgtEqual.state());
        assertEquals(PolicyInstallMonitoringService.PARITY_EQUAL, fgtEqual.haParity());

        DerivedStatus fgtDiffer = PolicyInstallMonitoringService.deriveFortiGateDirectState("FAIL", "MISMATCH", "CHECKSUM_DIFFERS");
        assertEquals(PolicyInstallMonitoringService.STATE_NOT_APPLICABLE, fgtDiffer.state());
        assertEquals(PolicyInstallMonitoringService.PARITY_DIFFER, fgtDiffer.haParity());

        DerivedStatus fgtUnknown = PolicyInstallMonitoringService.deriveFortiGateDirectState(null, null, null);
        assertEquals(PolicyInstallMonitoringService.STATE_NOT_APPLICABLE, fgtUnknown.state());
        assertEquals(PolicyInstallMonitoringService.PARITY_UNKNOWN, fgtUnknown.haParity());

        DerivedStatus fmgUnknown = PolicyInstallMonitoringService.deriveFortiManagerState();
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, fmgUnknown.state());
        assertTrue(fmgUnknown.reason().contains("needs a read not approved yet"));
    }

    @Test
    void candidateReadsGi1Gi2Gi3StayUnknownWithNeedsReadNotApprovedYetReason() {
        Instant t12 = Instant.parse("2026-11-17T12:00:00Z");
        DevicePolicyInstall vs0Install = new DevicePolicyInstall(
                "dev-vsx-1",
                Optional.of("VS-Policy"),
                Optional.of("17Nov2026 12:00:00"),
                Optional.of(t12),
                "cp_policy_install_cpstat_vsenv0",
                Optional.of(t12)
        );

        // Non-VS0 context requires GI-1 -> UNKNOWN with "needs a read not approved yet"
        DerivedStatus gi1Required = PolicyInstallMonitoringService.deriveCheckPointState(
                "VS-Policy", "3", Boolean.TRUE, vs0Install, t12
        );
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, gi1Required.state());
        assertTrue(gi1Required.reason().contains("needs a read not approved yet"));

        // Panorama unknown sync / missing job history requires GI-2 -> UNKNOWN with "needs a read not approved yet"
        DerivedStatus gi2Required = PolicyInstallMonitoringService.derivePaloAltoState(
                "DG-Branch", "UNKNOWN", true, null, t12
        );
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, gi2Required.state());
        assertTrue(gi2Required.reason().contains("needs a read not approved yet"));

        // FortiManager requires GI-3 -> UNKNOWN with "needs a read not approved yet"
        DerivedStatus gi3Required = PolicyInstallMonitoringService.deriveFortiManagerState();
        assertEquals(PolicyInstallMonitoringService.STATE_UNKNOWN, gi3Required.state());
        assertTrue(gi3Required.reason().contains("needs a read not approved yet"));
    }

    @Test
    void aiviewMaskingPseudonymizesNamesAndEvaluatesSearchAfterMasking() {
        Instant t10 = Instant.parse("2026-11-17T10:00:00Z");
        Instant t12 = Instant.parse("2026-11-17T12:00:00Z");

        PolicySnapshot.Metadata cpPkg = new PolicySnapshot.Metadata(
                "pkg-secret-1", "mds-1", "Customer-MDS", "CP", "dom-secret", "Customer-Finance-Domain", "Finance-ACL-Policy",
                t12.toString(), "art-1", List.of()
        );
        CpObjectInventory.Item gwItem = new CpObjectInventory.Item(
                "obj-gw-1", "uid-gw-1", "fw-customer-dc1", "simple-gateway", List.of(), List.of(), null,
                List.of(new CpObjectInventory.Installation("Finance-ACL-Policy", Boolean.TRUE))
        );
        DomainGatewayInventory domInv = new DomainGatewayInventory(List.of(gwItem), Map.of("uid-gw-1", "dev-cp-fin-1"));
        DevicePolicyInstall cpDirect = new DevicePolicyInstall(
                "dev-cp-fin-1", Optional.of("Finance-ACL-Policy"), Optional.of("17Nov2026 12:00:00"), Optional.of(t12),
                "cp_policy_install_cpstat", Optional.of(t12)
        );
        AsaInput asa = new AsaInput("dev-asa-1", "asa-customer-edge", "MATCH", t12, null);
        FortiGateInput fgt = new FortiGateInput("dev-fgt-1", "fgt-customer-core", "gateway", "PASS", "MATCH", null, t12);

        StoredEvidence evidence = new StoredEvidence(
                List.of(cpPkg),
                Map.of("mds-1:dom-secret", domInv),
                Map.of("pkg-secret-1", t10),
                Map.of("dev-cp-fin-1", cpDirect),
                List.of(asa),
                List.of(fgt)
        );

        PolicyInstallMonitoringService service = new PolicyInstallMonitoringService(() -> evidence, names);

        Map<String, Object> maskedResp = service.status(new Request("", "", "", 0), true);
        assertEquals(Boolean.TRUE, maskedResp.get("masked"));
        assertEquals(3, maskedResp.get("total"));

        String dumped = maskedResp.toString();
        assertFalse(dumped.contains("fw-customer-dc1"));
        assertFalse(dumped.contains("Customer-Finance-Domain"));
        assertFalse(dumped.contains("Finance-ACL-Policy"));
        assertFalse(dumped.contains("asa-customer-edge"));
        assertFalse(dumped.contains("fgt-customer-core"));

        // Probing raw customer hostname in aiview search MUST return 0 rows.
        Map<String, Object> rawProbe = service.status(new Request("", "", "fw-customer-dc1", 0), true);
        assertEquals(0, rawProbe.get("total"));

        // Searching by the masked pseudonym in aiview returns the matching row.
        String expectedPseudoHost = names.maskDeviceName("dev-cp-fin-1", null, "dev-cp-fin-1");
        Map<String, Object> pseudoSearch = service.status(new Request("", "", expectedPseudoHost, 0), true);
        assertEquals(1, pseudoSearch.get("total"));
    }

    /** Masking leak audit round 4 (2026-11-25): a virtual-system name in the context column and row id is configuration text. */
    @Test
    void aiviewMasksTheVirtualSystemContextInTheColumnAndTheRowId() {
        Instant t12 = Instant.parse("2026-11-17T12:00:00Z");
        String vsys = "vsys-synthetic-cardholder";
        PolicySnapshot.Metadata panPkg = new PolicySnapshot.Metadata(
                "pkg-pan-1", "pano-1", "Customer-Panorama", "PAN", "dg-1", "Customer-DG", "Customer-Pkg",
                t12.toString(), "art-pan", List.of(new PolicySnapshot.Target("dev-pan-1", "fw-pan-1", vsys, "IN_SYNC")));
        StoredEvidence evidence = new StoredEvidence(List.of(panPkg), Map.of(), Map.of(), Map.of(), List.of(), List.of());
        PolicyInstallMonitoringService service = new PolicyInstallMonitoringService(() -> evidence, names);

        String masked = service.status(new Request("", "", "", 0), true).toString();
        assertFalse(masked.contains(vsys), "the replay viewer never receives the virtual-system name");
        String raw = service.status(new Request("", "", "", 0), false).toString();
        assertTrue(raw.contains(vsys), "operators keep the real context");
    }
}
