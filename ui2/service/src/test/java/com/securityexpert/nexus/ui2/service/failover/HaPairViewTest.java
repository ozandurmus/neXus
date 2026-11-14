package com.securityexpert.nexus.ui2.service.failover;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

class HaPairViewTest {
    private static DeviceSummaryRecord member(String id, String vendor, String role) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.ofNullable(role), Optional.of("CLS-TEST-01"));
    }
    private static HaPairView.View asa(String a, String b) {
        return HaPairView.of(FailoverVendor.CISCO_ASA, List.of(member("m2", "cisco_asa", b), member("m1", "cisco_asa", a)));
    }
    private static HaPairView.View forti(String a, String b) {
        return HaPairView.of(FailoverVendor.FORTINET, List.of(member("m1", "fortinet", a), member("m2", "fortinet", b)));
    }

    @Test void asaHealthyPair() {
        var v = asa("active", "standby");
        assertEquals("HEALTHY", v.state());
        assertNull(v.reason());
        assertEquals(List.of(new HaPairView.Member("m1", "active"), new HaPairView.Member("m2", "standby")), v.members());
        assertEquals("UNKNOWN", v.sync());
    }
    @Test void asaPairWithAFailedMember() {
        var v = asa("active", "failed");
        assertEquals("DEGRADED", v.state());
        assertEquals("MEMBER_FAILED", v.reason());
        assertEquals("failed", v.members().get(1).role());
        assertEquals("DEGRADED", asa("failed", null).state());
    }
    @Test void asaMissingDataIsUnknownNeverGuessed() {
        var v = asa("active", null);
        assertEquals("UNKNOWN", v.state());
        assertEquals("ROLE_NOT_OBSERVED", v.reason());
        assertEquals("unknown", v.members().get(1).role());
        assertEquals("UNKNOWN", asa(null, "  ").state());
        assertEquals("ROLE_NOT_RECOGNISED", asa("active", "cold standby").reason());
        assertEquals("PAIR_INCOMPLETE", HaPairView.of(FailoverVendor.CISCO_ASA, List.of(member("m1", "cisco_asa", "active"))).reason());
    }
    @Test void fortiHealthyPairKeepsPrimarySecondaryWords() {
        var v = forti("secondary", "primary");
        assertEquals("HEALTHY", v.state());
        assertEquals("secondary", v.members().get(0).role());
        assertEquals("primary", v.members().get(1).role());
        assertEquals("NOT_COLLECTED", v.syncReason());
    }
    @Test void fortiPairWithBothMembersClaimingPrimaryIsDegraded() {
        var v = forti("primary", "primary");
        assertEquals("DEGRADED", v.state());
        assertEquals("ROLES_NOT_COMPLEMENTARY", v.reason());
        assertEquals("DEGRADED", forti("primary", "failed").state());
    }
    @Test void fortiMissingDataIsUnknownAndAsaWordsAreNotAcceptedForForti() {
        assertEquals("UNKNOWN", forti("primary", null).state());
        assertEquals("ROLE_NOT_RECOGNISED", forti("active", "standby").reason());
    }
    @Test void onlyAsaAndFortiAreCovered() {
        assertTrue(HaPairView.covers(FailoverVendor.CISCO_ASA));
        assertTrue(HaPairView.covers(FailoverVendor.FORTINET));
        assertFalse(HaPairView.covers(FailoverVendor.CHECK_POINT));
        assertFalse(HaPairView.covers(FailoverVendor.PALO_ALTO));
        var map = asa("active", "standby").toMap();
        assertEquals("HEALTHY", map.get("state"));
        assertEquals(2, ((List<?>) map.get("members")).size());
    }
}
