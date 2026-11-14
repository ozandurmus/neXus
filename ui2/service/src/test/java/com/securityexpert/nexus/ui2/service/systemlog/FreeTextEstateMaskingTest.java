package com.securityexpert.nexus.ui2.service.systemlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.service.privacy.FreeTextEstate;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/** Masking audit 2026-11-15: a serial or an administrator name inside free text, on a process that has masked nothing yet. */
class FreeTextEstateMaskingTest {
    private static final byte[] KEY = String.join("-", "synthetic", "free", "text", "masking", "key", "0001").getBytes(StandardCharsets.UTF_8);
    private final Random random = new Random();
    private final String serial = "ZQ" + Long.toHexString(random.nextLong() >>> 16).toUpperCase(java.util.Locale.ROOT);
    private final String user = "uq" + Long.toHexString(random.nextLong() >>> 24);

    private PrivacyMaskingResponseBodyAdvice advice(FreeTextEstate estate) {
        var names = new TopologyNamePseudonymizer(KEY);
        names.useFreeTextEstate(estate);
        return new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(KEY), names);
    }

    private FreeTextEstate estate(List<String> users) {
        return FreeTextEstateConfiguration.create(KEY, () -> List.of(EstateIdentity.serial(serial),
                EstateIdentity.address("203.0.113.9")), () -> users);
    }

    @Test
    void serialAndAdministratorNameInFreeTextAreMaskedOnAColdProcess() {
        var advice = advice(estate(List.of(user)));
        for (String key : List.of("terminal_reason", "message", "observed_value", "reason", "detail", "failedCheck")) {
            Object out = advice.maskObject(Map.of(key, "approved by " + user + " for unit " + serial + " (retry)"), null);
            String text = String.valueOf(((Map<?, ?>) out).get(key));
            assertThat(text).as(key).doesNotContain(user, serial).contains("USR-", "SN-").endsWith("(retry)");
        }
    }

    @Test
    void sameValuesGetTheTokensTheKeyRulesGive() {
        var names = new TopologyNamePseudonymizer(KEY);
        names.useFreeTextEstate(estate(List.of(user)));
        assertThat(names.maskText("serial " + serial)).isEqualTo("serial " + names.maskSerial(serial));
        assertThat(names.maskText("by " + user)).isEqualTo("by " + names.identityTokens().user(user));
    }

    @Test
    void ordinaryWordsSizesTimestampsAndMonthNamesStayAsTheyAre() {
        var names = new TopologyNamePseudonymizer(KEY);
        // Months and generic words stored as administrator names are not identities.
        names.useFreeTextEstate(estate(List.of("March", "May", "admin", "Any", user)));
        String text = "On March 5 2026 10:15:00 UTC the backup of 512 KB, 3 MB and 1.2 GB failed in May; any admin may retry";
        assertThat(names.maskText(text)).isEqualTo(text);
    }

    @Test
    void neverLoadedEstateDictionaryWithholdsTheTextAndRetriesLater() {
        int[] calls = {0};
        var failing = new FreeTextEstate(() -> {
            calls[0]++;
            throw new IllegalStateException("store down");
        });
        var names = new TopologyNamePseudonymizer(KEY);
        names.useFreeTextEstate(failing);
        assertThat(names.maskText("unit " + serial)).isEqualTo(FreeTextEstate.UNAVAILABLE);
        assertThat(names.maskText("unit " + serial)).isEqualTo(FreeTextEstate.UNAVAILABLE);
        assertThat(calls[0]).as("no read storm while waiting to retry").isEqualTo(1);
    }

    @Test
    void neverLoadedDeviceDirectoryWithholdsTheTextUntilItLoads() {
        boolean[] down = {true};
        var names = new TopologyNamePseudonymizer(KEY);
        names.setDeviceDirectory(() -> {
            if (down[0]) throw new IllegalStateException("db down");
            return List.of();
        });
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(KEY), names);
        Object out = advice.maskObject(Map.of("message", "ssh to some-host failed"), null);
        assertThat(((Map<?, ?>) out).get("message")).isEqualTo(FreeTextEstate.UNAVAILABLE);
        // Hostname keys are still masked as names; only free text is withheld.
        down[0] = false;
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (FreeTextEstate.UNAVAILABLE.equals(names.maskText("ssh to some-host failed")) && System.nanoTime() < until) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(names.maskText("ssh to some-host failed")).isEqualTo("ssh to some-host failed");
    }

    @Test
    void refreshFailureAfterASuccessfulLoadKeepsThePreviousState() {
        boolean[] fail = {false};
        var estate = FreeTextEstateConfiguration.create(KEY, () -> {
            if (fail[0]) throw new IllegalStateException("db down");
            return List.of(EstateIdentity.serial(serial));
        }, List::of);
        assertThat(estate.mask("unit " + serial)).contains("SN-").doesNotContain(serial);
        fail[0] = true;
        // Within the source's TTL the cached dictionary answers; the failed-refresh branch never yields the placeholder.
        assertThat(estate.mask("unit " + serial)).contains("SN-").doesNotContain(serial);
    }

    @Test
    void unmaskedResponsesNeverReachTheMaskingPath() {
        var names = new TopologyNamePseudonymizer(KEY);
        names.useFreeTextEstate(new FreeTextEstate(() -> {
            throw new IllegalStateException("store down");
        }));
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(KEY), names);
        var request = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        var serverRequest = org.mockito.Mockito.mock(org.springframework.http.server.ServletServerHttpRequest.class);
        org.mockito.Mockito.when(serverRequest.getServletRequest()).thenReturn(request);
        org.mockito.Mockito.when(request.getAttribute(
                com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(false);
        Map<String, Object> body = Map.of("terminal_reason", "unit " + serial);
        assertThat(advice.beforeBodyWrite(body, null, null, null, serverRequest, null)).isSameAs(body);
    }
}
