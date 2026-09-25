package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InteractiveShellPromptVariantTest {

    @Test
    void fortiOsModePromptsMatchTheLearnedHost() {
        assertTrue(InteractiveShellSession.isPromptVariant("FGT-TANGO-04 #", "FGT-TANGO-04 (global) #"));
        assertTrue(InteractiveShellSession.isPromptVariant("FGT-TANGO-04 #", "FGT-TANGO-04 (root) #"));
        assertTrue(InteractiveShellSession.isPromptVariant("FGT-TANGO-04 (global) #", "FGT-TANGO-04 #"));
        assertFalse(InteractiveShellSession.isPromptVariant("FGT-TANGO-04 #", "OTHER-HOST (root) #"));
        assertFalse(InteractiveShellSession.isPromptVariant("FGT-TANGO-04 #", "set comment \"FGT-TANGO-04 x\""));
        assertTrue(InteractiveShellSession.isPromptVariant("[Expert@cp-gw:0]#", "[Expert@cp-gw:0]#"));
    }
}
