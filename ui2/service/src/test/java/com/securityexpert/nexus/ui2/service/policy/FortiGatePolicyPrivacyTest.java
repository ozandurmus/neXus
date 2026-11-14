package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/** The aiview projection of a FortiGate snapshot keeps the fixed vocabulary and masks configuration text and addresses. */
class FortiGatePolicyPrivacyTest {
    @Test void configurationNamesAndAddressesAreMaskedAndFixedTokensKept() {
        var s = FortiGatePolicyEvaluationTest.fgt(FortiGatePolicyEvaluationTest.policy(7,
                "name \"POL-WEB-PUBLISH\"", "srcintf \"port1\"", "dstintf \"port2\"", "srcaddr \"all\"", "dstaddr \"HOST-WEB\"",
                "schedule \"always\"", "service \"HTTPS\"", "action accept", "logtraffic all"));
        @SuppressWarnings("unchecked") Map<String, Object> raw = new ObjectMapper().convertValue(s, Map.class);
        String masked = String.valueOf(PolicyPrivacy.mask(raw, "", new TopologyNamePseudonymizer(new byte[32]), new SubnetPreservingIpMasker(new byte[32])));
        for (String secret : new String[] {"POL-WEB-PUBLISH", "HOST-WEB", "198.51.100.20", "port1", "port2", "FortiGate dev-fgt-1"})
            assertFalse(masked.contains(secret), secret + " leaked");
        // The device id is opaque (sourceId, targets[].deviceId) and passes as such, never as a name.
        for (String fixed : new String[] {"vendor=FGT", "action=accept", "FGT implicit deny", "source=FGT firewall policy", "log=all"})
            assertTrue(masked.contains(fixed), fixed + " missing");
    }
}
