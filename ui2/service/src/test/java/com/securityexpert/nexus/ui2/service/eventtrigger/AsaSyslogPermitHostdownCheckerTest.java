package com.securityexpert.nexus.ui2.service.eventtrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securityexpert.nexus.ui2.service.eventtrigger.AsaSyslogPermitHostdownChecker.Assessment;
import com.securityexpert.nexus.ui2.service.eventtrigger.AsaSyslogPermitHostdownChecker.FleetSummary;
import com.securityexpert.nexus.ui2.service.eventtrigger.AsaSyslogPermitHostdownChecker.PermitHostdownStatus;
import com.securityexpert.nexus.ui2.service.eventtrigger.AsaSyslogPermitHostdownChecker.SyslogTransport;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AsaSyslogPermitHostdownCheckerTest {

    @Test
    void presentPermitHostdownPermitsDirectTcpOrTls() {
        String config = """
                : Saved
                ASA Version 9.18(4)
                hostname FW-SYNTHETIC-01
                logging enable
                logging permit-hostdown
                logging host mgmt 192.0.2.10 6/6514 secure
                : end
                """;

        Assessment assessment = AsaSyslogPermitHostdownChecker.assess(config);

        assertThat(assessment.permitHostdown()).isEqualTo(PermitHostdownStatus.PRESENT);
        assertThat(assessment.directTcpOrTlsPermitted()).isTrue();
        assertThat(assessment.configuredTransports()).containsExactly(SyslogTransport.TLS_SECURE);
        assertThat(assessment.loggingHostLineCount()).isEqualTo(1);
    }

    @Test
    void absentPermitHostdownFailsClosed() {
        String config = """
                ASA Version 9.18(4)
                hostname FW-SYNTHETIC-02
                logging enable
                logging host mgmt 192.0.2.10 tcp/6514
                : end
                """;

        Assessment assessment = AsaSyslogPermitHostdownChecker.assess(config);

        assertThat(assessment.permitHostdown()).isEqualTo(PermitHostdownStatus.ABSENT);
        assertThat(assessment.directTcpOrTlsPermitted()).isFalse();
        assertThat(assessment.configuredTransports()).containsExactly(SyslogTransport.TCP);
        assertThat(assessment.loggingHostLineCount()).isEqualTo(1);
    }

    @Test
    void lastDirectiveWinsBetweenPresentAndNoLoggingPermitHostdown() {
        String disabledAfterPresent = """
                ASA Version 9.18(4)
                logging enable
                logging permit-hostdown
                logging host mgmt 192.0.2.10 6/6514 secure
                no logging permit-hostdown
                : end
                """;

        Assessment disabledAssessment = AsaSyslogPermitHostdownChecker.assess(disabledAfterPresent);
        assertThat(disabledAssessment.permitHostdown()).isEqualTo(PermitHostdownStatus.DISABLED);
        assertThat(disabledAssessment.directTcpOrTlsPermitted()).isFalse();

        String presentAfterDisabled = """
                ASA Version 9.18(4)
                logging enable
                no logging permit-hostdown
                logging host mgmt 192.0.2.10 6/6514 secure
                logging permit-hostdown
                : end
                """;

        Assessment reEnabledAssessment = AsaSyslogPermitHostdownChecker.assess(presentAfterDisabled);
        assertThat(reEnabledAssessment.permitHostdown()).isEqualTo(PermitHostdownStatus.PRESENT);
        assertThat(reEnabledAssessment.directTcpOrTlsPermitted()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "   \n\t  ",
        "logging permit-hostdown\n--More--\n",
        "logging permit-hostdown\n\u0000truncated",
        "logging permit-hostdown\n\uFFFDcorrupted",
        "logging permit-hostdown\n[TRUNCATED]"
    })
    void nullBlankOrTruncatedUnreadableConfigFailsClosedToUnknown(String config) {
        Assessment assessment = AsaSyslogPermitHostdownChecker.assess(config);

        assertThat(assessment.permitHostdown()).isEqualTo(PermitHostdownStatus.UNKNOWN);
        assertThat(assessment.directTcpOrTlsPermitted()).isFalse();
        assertThat(assessment.configuredTransports()).isEmpty();
        assertThat(assessment.loggingHostLineCount()).isZero();
    }

    private static void assertFailsClosedToUnknown(String config) {
        Assessment assessment = AsaSyslogPermitHostdownChecker.assess(config);

        assertThat(assessment.permitHostdown()).isEqualTo(PermitHostdownStatus.UNKNOWN);
        assertThat(assessment.directTcpOrTlsPermitted()).isFalse();
        assertThat(assessment.configuredTransports()).isEmpty();
        assertThat(assessment.loggingHostLineCount()).isZero();
    }

    @Test
    void oversizedConfigFailsClosedToUnknown() {
        String head = "ASA Version 9.18(4)\nlogging permit-hostdown\n";
        String padding = "!\n".repeat(AsaSyslogPermitHostdownChecker.MAX_CONFIG_CHARS / 2);
        String oversized = head + padding + ": end\n";
        assertThat(oversized.length()).isGreaterThan(AsaSyslogPermitHostdownChecker.MAX_CONFIG_CHARS);

        assertFailsClosedToUnknown(oversized);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "just some arbitrary text\nlogging host mgmt 192.0.2.10\n",
        "<html><body>503 Service Unavailable</body></html>\n",
        "% Invalid input detected at '^' marker.\n",
        "hostname FW-TANGO-04\nlogging enable\nlogging host mgmt 192.0.2.10\n",
        "ASA Version 9.18(4)\nlogging enable\nlogging host mgmt 192.0.2.10\n",
        "hostname FW-TANGO-04\nlogging enable\n: end\n",
        "  ASA Version 9.18(4)\n  logging host mgmt 192.0.2.10\n  : end\n"
    })
    void nonAsaOrUnanchoredTextFailsClosedToUnknownNotAbsent(String config) {
        assertFailsClosedToUnknown(config);
    }

    @Test
    void fullyIndentedConfigFailsClosedToUnknown() {
        String config = """
                  ASA Version 9.18(4)
                  logging enable
                  logging host mgmt 192.0.2.10 6/6514 secure
                  : end
                """;

        assertFailsClosedToUnknown(config);
    }

    @Test
    void multiContextConfigFailsClosedToUnknown() {
        String modeMultiple = """
                ASA Version 9.18(4)
                hostname FW-TANGO-04
                mode multiple
                logging permit-hostdown
                admin-context admin
                context admin
                 allocate-interface Management1/1
                : end
                """;
        String contextBlockOnly = """
                ASA Version 9.18(4)
                logging permit-hostdown
                context CTX-TANGO-A
                 config-url disk0:/ctx-a.cfg
                : end
                """;

        assertFailsClosedToUnknown(modeMultiple);
        assertFailsClosedToUnknown(contextBlockOnly);
    }

    @Test
    void fleetSummaryRejectsCountsThatDoNotSumToTotal() {
        assertThatThrownBy(() -> new FleetSummary(
                        3, 2, 0, 0, 0, true, java.util.Map.of()))
                .as("total greater than classified units")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FleetSummary(
                        1, 1, 1, 0, 0, true, java.util.Map.of()))
                .as("total smaller than classified units")
                .isInstanceOf(IllegalArgumentException.class);

        FleetSummary consistent = new FleetSummary(2, 2, 0, 0, 0, false, java.util.Map.of());
        assertThat(consistent.asaDirectTcpTlsSafe()).isTrue();
    }

    @Test
    void classifiesLoggingHostTransportsAndIgnoresNoLoggingHost() {
        String config = """
                ASA Version 9.18(4)
                logging permit-hostdown
                logging host mgmt 192.0.2.10
                logging host mgmt 192.0.2.11 udp/514
                logging host mgmt 192.0.2.12 17/514
                logging host mgmt 192.0.2.13 tcp/6514
                logging host mgmt 192.0.2.14 6/1470
                logging host mgmt 192.0.2.15 6/6514 secure
                logging host mgmt 192.0.2.16 tcp/6514 format emblem secure
                no logging host mgmt 192.0.2.17 sctp/9999
                : end
                """;

        Assessment assessment = AsaSyslogPermitHostdownChecker.assess(config);

        assertThat(assessment.permitHostdown()).isEqualTo(PermitHostdownStatus.PRESENT);
        assertThat(assessment.directTcpOrTlsPermitted()).isTrue();
        assertThat(assessment.loggingHostLineCount()).isEqualTo(7);
        assertThat(assessment.configuredTransports())
                .containsExactlyInAnyOrder(
                        SyslogTransport.UDP,
                        SyslogTransport.TCP,
                        SyslogTransport.TLS_SECURE);
    }

    @Test
    void unrecognizedLoggingHostTransportTokenClassifiesAsUnknown() {
        String unrecognizedProto = """
                ASA Version 9.18(4)
                logging permit-hostdown
                logging host mgmt 192.0.2.20 sctp/6514
                logging host mgmt 192.0.2.21 udp/514 secure
                logging host mgmt 192.0.2.22 6/99999
                : end
                """;

        Assessment assessment = AsaSyslogPermitHostdownChecker.assess(unrecognizedProto);

        assertThat(assessment.loggingHostLineCount()).isEqualTo(3);
        assertThat(assessment.configuredTransports()).containsExactly(SyslogTransport.UNKNOWN);
    }

    @Test
    void commentedOrIndentedPermitHostdownDoesNotCountAsPresent() {
        String config = """
                ASA Version 9.18(4)
                ! logging permit-hostdown
                policy-map global_policy
                 logging permit-hostdown
                 logging host inside 192.0.2.30 6/6514 secure
                logging host mgmt 192.0.2.31
                : end
                """;

        Assessment assessment = AsaSyslogPermitHostdownChecker.assess(config);

        assertThat(assessment.permitHostdown()).isEqualTo(PermitHostdownStatus.ABSENT);
        assertThat(assessment.directTcpOrTlsPermitted()).isFalse();
        assertThat(assessment.loggingHostLineCount()).isEqualTo(1);
        assertThat(assessment.configuredTransports()).containsExactly(SyslogTransport.UDP);
    }

    @Test
    void assessmentRecordEnforcesDirectTcpOrTlsPermittedInvariant() {
        Assessment forgedAbsent = new Assessment(
                PermitHostdownStatus.ABSENT, Set.of(SyslogTransport.TLS_SECURE), 1, true);
        Assessment forgedDisabled = new Assessment(
                PermitHostdownStatus.DISABLED, Set.of(SyslogTransport.TCP), 1, true);
        Assessment forgedUnknown = new Assessment(
                PermitHostdownStatus.UNKNOWN, Set.of(), 0, true);

        assertThat(forgedAbsent.directTcpOrTlsPermitted()).isFalse();
        assertThat(forgedDisabled.directTcpOrTlsPermitted()).isFalse();
        assertThat(forgedUnknown.directTcpOrTlsPermitted()).isFalse();
    }

    @Test
    void fleetSummaryAggregatesFc6CountsAndDirectTcpTlsSafety() {
        FleetSummary emptySummary = AsaSyslogPermitHostdownChecker.summarize(List.of());
        assertThat(emptySummary.asaTotalCount()).isZero();
        assertThat(emptySummary.asaDirectTcpTlsSafe()).isFalse();
        assertThat(emptySummary.transportUnitCounts().get(SyslogTransport.UDP)).isZero();

        Assessment unit1 = AsaSyslogPermitHostdownChecker.assess("""
                ASA Version 9.18(4)
                logging permit-hostdown
                logging host mgmt 192.0.2.10 6/6514 secure
                logging host mgmt 192.0.2.11 6/6515 secure
                logging host mgmt 192.0.2.12
                : end
                """);
        Assessment unit2 = AsaSyslogPermitHostdownChecker.assess("""
                ASA Version 9.18(4)
                logging permit-hostdown
                logging host mgmt 192.0.2.20 tcp/6514
                : end
                """);

        FleetSummary allSafeSummary = AsaSyslogPermitHostdownChecker.summarize(List.of(unit1, unit2));
        assertThat(allSafeSummary.asaTotalCount()).isEqualTo(2);
        assertThat(allSafeSummary.asaPermitHostdownPresentCount()).isEqualTo(2);
        assertThat(allSafeSummary.asaPermitHostdownAbsentCount()).isZero();
        assertThat(allSafeSummary.asaPermitHostdownDisabledCount()).isZero();
        assertThat(allSafeSummary.asaPermitHostdownUnknownCount()).isZero();
        assertThat(allSafeSummary.asaDirectTcpTlsSafe()).isTrue();
        assertThat(allSafeSummary.transportUnitCounts().get(SyslogTransport.TLS_SECURE)).isEqualTo(1);
        assertThat(allSafeSummary.transportUnitCounts().get(SyslogTransport.UDP)).isEqualTo(1);
        assertThat(allSafeSummary.transportUnitCounts().get(SyslogTransport.TCP)).isEqualTo(1);
        assertThat(allSafeSummary.transportUnitCounts().get(SyslogTransport.UNKNOWN)).isZero();

        Assessment unit3Absent = AsaSyslogPermitHostdownChecker.assess("ASA Version 9.18(4)\nlogging host mgmt 192.0.2.30\n: end\n");
        Assessment unit4Disabled = AsaSyslogPermitHostdownChecker.assess("ASA Version 9.18(4)\nno logging permit-hostdown\n: end\n");
        Assessment unit5Unknown = AsaSyslogPermitHostdownChecker.assess((String) null);

        FleetSummary mixedSummary = AsaSyslogPermitHostdownChecker.summarize(
                Arrays.asList(unit1, unit2, unit3Absent, unit4Disabled, unit5Unknown));
        assertThat(mixedSummary.asaTotalCount()).isEqualTo(5);
        assertThat(mixedSummary.asaPermitHostdownPresentCount()).isEqualTo(2);
        assertThat(mixedSummary.asaPermitHostdownAbsentCount()).isEqualTo(1);
        assertThat(mixedSummary.asaPermitHostdownDisabledCount()).isEqualTo(1);
        assertThat(mixedSummary.asaPermitHostdownUnknownCount()).isEqualTo(1);
        assertThat(mixedSummary.asaDirectTcpTlsSafe()).isFalse();
        assertThat(mixedSummary.transportUnitCounts().get(SyslogTransport.UDP)).isEqualTo(2);
    }

    @Test
    void rawEvidenceNeverLeaksIntoAssessmentOrFleetSummaryToString() {
        String plantedIp = String.join(".", "198", "51", "100", "77");
        String plantedInterface = "mgmt-secret";
        String plantedHostname = "asa-raw-unit";

        String config = String.join(
                "\n",
                "ASA Version 9.18(4)",
                "hostname " + plantedHostname,
                "logging permit-hostdown",
                "logging host " + plantedInterface + " " + plantedIp + " 6/6514 secure",
                "logging host " + plantedInterface + " " + plantedIp + " bogus-proto",
                ": end");

        Assessment assessment = AsaSyslogPermitHostdownChecker.assess(config);
        FleetSummary summary = AsaSyslogPermitHostdownChecker.summarize(List.of(assessment));

        String assessmentText = assessment.toString();
        String summaryText = summary.toString();

        for (String planted : List.of(plantedIp, plantedInterface, plantedHostname, "6514", "bogus-proto")) {
            assertThat(assessmentText).doesNotContainIgnoringCase(planted);
            assertThat(summaryText).doesNotContainIgnoringCase(planted);
        }
    }
}
