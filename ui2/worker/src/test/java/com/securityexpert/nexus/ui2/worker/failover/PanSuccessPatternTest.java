package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** PAN-OS answers {@code <response status = 'success'>} (spaces, single quotes) as well as the double-quoted form. */
class PanSuccessPatternTest {
    private static final Pattern SUCCESS = Pattern.compile("status\\s*=\\s*[\"']success[\"']");

    @Test void acceptsBothQuotingStyles() {
        assertTrue(SUCCESS.matcher("<response status = 'success'><result><key>k</key></result></response>").find());
        assertTrue(SUCCESS.matcher("<response status=\"success\"><result/></response>").find());
        assertFalse(SUCCESS.matcher("<response status = 'error'><msg>denied</msg></response>").find());
    }
}
