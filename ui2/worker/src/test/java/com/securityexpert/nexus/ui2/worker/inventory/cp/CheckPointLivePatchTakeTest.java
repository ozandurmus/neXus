package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class CheckPointLivePatchTakeTest {

    @Test
    void takesTheHighestMatchingTake() {
        String out = "HOTFIX_R81_20_JUMBO_HF_MAIN     Take:  122\n"
                + "BUNDLE_URGENT_SECURITY_UPDATE_R81_20   Take:  7\n"
                + "  BUNDLE_URGENT_SECURITY_UPDATE_R81_20_B   Take:  12  \n";
        assertEquals(Optional.of("12"), CheckPointPlatformFactsParser.livePatchPackageTake(out));
    }

    @Test
    void absentOrNonMatchingLinesGiveNothing() {
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.livePatchPackageTake(null));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.livePatchPackageTake(""));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.livePatchPackageTake("HOTFIX_R81_20_JUMBO_HF_MAIN     Take:  122\n"));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.livePatchPackageTake("BUNDLE_URGENT_SECURITY_UPDATE_X Take: n/a\n"));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.livePatchPackageTake("BUNDLE_URGENT_SECURITY_UPDATE_X extra Take: 5\n"));
    }

    @Test
    void theUrgentShapeMasksDigitsAndDropsOtherCharacters() {
        String shape = CheckPointPlatformFactsParser.urgentShape("host-1 BUNDLE_URGENT_SECURITY_UPDATE_R81_20 Take: n/a 118\nnothing\n");
        assertTrue(shape.startsWith("urgentLines=1"), shape);
        assertTrue(shape.contains("BUNDLE_URGENT_SECURITY_UPDATE_R9_9 Take: n.a 9") || shape.contains("BUNDLE_URGENT_SECURITY_UPDATE_R9_9 Take: na 9"), shape);
    }
}
