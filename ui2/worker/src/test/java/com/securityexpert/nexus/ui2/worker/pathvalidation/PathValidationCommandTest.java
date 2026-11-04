package com.securityexpert.nexus.ui2.worker.pathvalidation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.HopPlan;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Check;

/** The approved literals are filled only with strictly validated values; every other value is refused before a command exists. */
class PathValidationCommandTest {

    @Test
    void theFilledCommandsAreTheApprovedLiteralsWithTheValuesInPlace() {
        assertEquals("bash -lc 'ip -4 route get 203.0.113.10'", PathValidation.cpRouteGet("203.0.113.10", Optional.empty()));
        assertEquals("bash -lc 'vsenv 2 && ip -4 route get 203.0.113.10'",
                PathValidation.cpRouteGet("203.0.113.10", Optional.of("2")));
        assertEquals("<test><routing><fib-lookup><virtual-router>vr-main</virtual-router><ip>203.0.113.10</ip></fib-lookup>"
                + "</routing></test>", PathValidation.panFibLookup("vr-main", "203.0.113.10"));
        assertEquals("<test><security-policy-match><from>ZONE-TRANSIT</from><to>ZONE-DMZ</to><source>198.51.100.20</source>"
                + "<destination>203.0.113.200</destination><protocol>6</protocol><destination-port>443</destination-port>"
                + "</security-policy-match></test>",
                PathValidation.panSecurityPolicyMatch("ZONE-TRANSIT", "ZONE-DMZ", "198.51.100.20", "203.0.113.200", 6, 443));
        assertEquals("<test><nat-policy-match><from>ZONE-TRANSIT</from><to>ZONE-DMZ</to><source>198.51.100.20</source>"
                + "<destination>203.0.113.200</destination><protocol>6</protocol><destination-port>443</destination-port>"
                + "</nat-policy-match></test>",
                PathValidation.panNatPolicyMatch("ZONE-TRANSIT", "ZONE-DMZ", "198.51.100.20", "203.0.113.200", 6, 443));
    }

    @Test
    void injectionAndMalformedValuesAreRefused() {
        for (String dst : List.of("203.0.113.10; reboot", "203.0.113.010", "203.0.113.256", "203.0.113.0/24",
                "2001:db8::1", "", " 203.0.113.10", "203.0.113.10'", "fw-tango-04"))
            assertThrows(IllegalArgumentException.class, () -> PathValidation.cpRouteGet(dst, Optional.empty()), dst);
        for (String vsid : List.of("2 && reboot", "-1", "02a", "", "123456", "2'"))
            assertThrows(IllegalArgumentException.class, () -> PathValidation.cpRouteGet("203.0.113.10", Optional.of(vsid)), vsid);
        for (String name : List.of("vr</virtual-router><x>", "a&b", "zone\"x", " leading", "trailing ",
                "0123456789012345678901234567890123", "", "zone\nx"))
            assertThrows(IllegalArgumentException.class, () -> PathValidation.panFibLookup(name, "203.0.113.10"), name);
        assertThrows(IllegalArgumentException.class,
                () -> PathValidation.panSecurityPolicyMatch("ZONE-A", "ZONE-B", "198.51.100.20", "203.0.113.200", 6, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PathValidation.panSecurityPolicyMatch("ZONE-A", "ZONE-B", "198.51.100.20", "203.0.113.200", 6, 65_536));
        var udp = assertThrows(IllegalArgumentException.class,
                () -> PathValidation.panNatPolicyMatch("ZONE-A", "ZONE-B", "198.51.100.20", "203.0.113.200", 17, 53));
        assertEquals(PathValidation.PROTOCOL_NOT_APPROVED, udp.getMessage(), "UDP has no approved literal");
        assertTrue(PathValidation.protocol(6) && PathValidation.protocol(17) && !PathValidation.protocol(1));
    }

    @Test
    void everyGateResolvesSignedOffFromTheFixtureAndAnAlteredGateDoesNot() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        for (PathValidation.Gate gate : PathValidation.GATES)
            assertEquals(gate.gateId(), PathValidation.resolve(gate, gates).orElseThrow().gateId());
        var forged = new PathValidation.Gate("path_validation_cp_route_get", "check_point", "cp_gaia_gateway", "expert",
                "SSH_EXEC", "bash -lc 'ip -4 route show'");
        assertTrue(PathValidation.resolve(forged, gates).isEmpty());
        assertTrue(PathValidation.resolve(PathValidation.CP_ROUTE_GET, key -> List.of()).isEmpty());
    }

    @Test
    void aPlanIsCheckedFieldByField() {
        var expect = new HopPlan.Expect("IP", "eth2", "192.0.2.2", false, null, null, null, null);
        var cp = new HopPlan(1, "check_point", "dev-t04", "physical", null, null, "203.0.113.10", null, null, 6, 443,
                null, null, null, List.of(Check.CP_ROUTE), expect);
        assertTrue(cp.invalid().isEmpty());
        assertEquals(Optional.of("CONTEXT_UNSUPPORTED"), new HopPlan(1, "check_point", "dev-t04", "2", null, null,
                "203.0.113.10", null, null, 6, 443, null, null, null, List.of(Check.CP_ROUTE), expect).invalid());
        assertEquals(Optional.of("VSID_INVALID"), new HopPlan(1, "check_point", "dev-t04", "3", "2", null,
                "203.0.113.10", null, null, 6, 443, null, null, null, List.of(Check.CP_ROUTE), expect).invalid());
        assertEquals(Optional.of("PLAN_CHECKS_INVALID"), new HopPlan(1, "check_point", "dev-t04", "physical", null, null,
                "203.0.113.10", null, null, 6, 443, null, null, null, List.of(Check.PAN_FIB), expect).invalid());
        var pan = new HopPlan(1, "palo_alto", "dev-b02", "vsys1", null, "vr-main", "203.0.113.10", "198.51.100.20",
                "203.0.113.10", 6, 443, "ZONE-TRANSIT", "ZONE-DMZ", "ZONE-DMZ",
                List.of(Check.PAN_FIB, Check.PAN_SECURITY, Check.PAN_NAT), expect);
        assertTrue(pan.invalid().isEmpty());
        assertEquals(Optional.of("POLICY_PARAMETER_INVALID"), new HopPlan(1, "palo_alto", "dev-b02", "vsys1", null,
                "vr-main", "203.0.113.10", "198.51.100.20", "203.0.113.10", 17, 53, "ZONE-TRANSIT", "ZONE-DMZ", null,
                List.of(Check.PAN_SECURITY), expect).invalid());
        assertEquals(Optional.of("FIB_PARAMETER_INVALID"), new HopPlan(1, "palo_alto", "dev-b02", "vsys1", null,
                "vr<x>", "203.0.113.10", null, null, 6, 443, null, null, null, List.of(Check.PAN_FIB), expect).invalid());
        assertEquals(Optional.of("VENDOR_UNSUPPORTED"), new HopPlan(1, "fortinet", "dev-x", "physical", null, null,
                "203.0.113.10", null, null, 6, 443, null, null, null, List.of(Check.PAN_FIB), expect).invalid());
    }
}
