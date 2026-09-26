package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import com.securityexpert.nexus.ui2.service.device.diagnostic.DiagnosticService;

class DiagnosticControllerTest {
    @Test
    void refusesCommandTextBeforeAdmission() {
        DiagnosticService service = mock(DiagnosticService.class);
        var controller = new DiagnosticController(service);
        var response = controller.run(Map.of("device_id", "device-1", "port", "port5",
                "request_id", "00000000-0000-0000-0000-000000000001", "command", "execute reboot"),
                mock(HttpServletRequest.class));
        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(service);
    }
}
