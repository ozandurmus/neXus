package com.securityexpert.nexus.ui2.worker.inventory.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Expected-shape fixtures (UNVERIFIED): replaced by the Product Owner's measured samples when recorded. */
class PolicyInstallParsersTest {

    @Test
    void checkPointNameAndInstallTimeAsGatewayLocalTime() {
        PolicyInstallRead r = CheckPointPolicyParser.parse("""
                Product name:         Firewall
                Policy name:          Standard_Policy
                Policy install time:  Tue Sep 22 23:05:11 2026
                Num. connections:     1234
                """);
        assertEquals(Optional.of("Standard_Policy"), r.policyName());
        assertEquals(Optional.of("Tue Sep 22 23:05:11 2026"), r.installedAtText());
        assertEquals(Optional.of(Instant.parse("2026-09-22T20:05:11Z")), r.installedAt());
    }

    @Test
    void checkPointUnparsableTimeKeepsTheTextAndLeavesTheInstantUnknown() {
        PolicyInstallRead r = CheckPointPolicyParser.parse("Policy name: P\nInstall time: sometime yesterday\n");
        assertEquals(Optional.of("sometime yesterday"), r.installedAtText());
        assertTrue(r.installedAt().isEmpty());
        assertEquals(PolicyInstallRead.NONE, CheckPointPolicyParser.parse(""));
    }

    @Test
    void anEmptyInstallTimeLineStaysEmptyAndNeverReadsTheNextLine() {
        PolicyInstallRead r = CheckPointPolicyParser.parse("Policy name:          Standard\nInstall time:\nNum. connections:     12\n");
        assertEquals(Optional.of("Standard"), r.policyName());
        assertTrue(r.installedAtText().isEmpty());
    }

    @Test
    void paloAltoLatestFinishedSuccessfulCommitWinsAndTheUserIsNeverRead() {
        PolicyInstallRead r = PaloAltoJobsParser.parse("""
                <response status="success"><result>
                  <job><type>Commit</type><status>FIN</status><result>OK</result><tfin>2026/09/21 23:02:10</tfin><user>someone</user></job>
                  <job><type>CommitAll</type><status>FIN</status><result>OK</result><tfin>2026/09/22 23:04:59</tfin><user>Panorama-push</user></job>
                  <job><type>Commit</type><status>FIN</status><result>FAIL</result><tfin>2026/09/23 01:00:00</tfin></job>
                  <job><type>AutoCom</type><status>FIN</status><result>OK</result><tfin>2026/09/23 02:00:00</tfin></job>
                  <job><type>Commit</type><status>ACT</status><result>PEND</result><tfin></tfin></job>
                </result></response>""");
        assertEquals(Optional.of("2026/09/22 23:04:59"), r.installedAtText());
        assertEquals(Optional.of(Instant.parse("2026-09-22T20:04:59Z")), r.installedAt());
        assertEquals("pan_show_jobs_all:CommitAll", r.sourceRead());
        assertTrue(r.policyName().isEmpty());
    }

    @Test
    void paloAltoDoctypeAndNoCommitAreUnknown() {
        assertEquals(PolicyInstallRead.NONE, PaloAltoJobsParser.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"));
        assertTrue(PaloAltoJobsParser.parse("<response><result></result></response>").installedAt().isEmpty());
    }
}
