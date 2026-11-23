package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** V266: the script request bodies bind the "mail on failure only" option through the canonical record constructor. */
class ScriptExecutionRequestJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void createAndPatchBindTheMailOnFailureOption() throws Exception {
        var create = json.readValue("{\"name\":\"nightly-export\",\"kind\":\"sh\",\"body\":\"echo ok\","
                + "\"notify\":\"smtp\",\"notify_mail_on_failure_only\":true}",
                ScriptExecutionController.CreateScriptRequest.class);
        assertEquals("smtp", create.notifyMode());
        assertTrue(create.notifyMailOnFailureOnly());

        var patch = json.readValue("{\"notify\":\"both\",\"notifyMailOnFailureOnly\":false}",
                ScriptExecutionController.PatchSettingsRequest.class);
        assertEquals("both", patch.notifyMode());
        assertEquals(Boolean.FALSE, patch.notifyMailOnFailureOnly());

        var older = json.readValue("{\"notify\":\"syslog\"}", ScriptExecutionController.PatchSettingsRequest.class);
        assertNull(older.notifyMailOnFailureOnly(), "an older client leaves the option unchanged");
    }
}
