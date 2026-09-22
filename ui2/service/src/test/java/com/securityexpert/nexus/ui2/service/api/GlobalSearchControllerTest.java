package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

class GlobalSearchControllerTest {
    @Test
    void refusesMissingShortAndLongQueries() {
        var controller = new GlobalSearchController(null);
        var request = new MockHttpServletRequest();
        assertEquals(HttpStatus.BAD_REQUEST, controller.search(null, null, request).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.search("a", null, request).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.search("a".repeat(101), null, request).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.search("ok", 0, request).getStatusCode());
    }

    @Test
    void readActionHasTheSameRoleAsConfigurationText() {
        var actions = new ActionRegistry();
        assertEquals(actions.find(ActionRegistry.DEVICE_CONFIGURATION_TEXT_READ).orElseThrow().requiredRoleToken(),
                actions.find(ActionRegistry.GLOBAL_SEARCH_READ).orElseThrow().requiredRoleToken());
    }
}
