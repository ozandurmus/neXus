package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class DatabaseBusyAdviceTest {
    @RestController
    static class FailingController {
        @GetMapping("/direct") Object direct() throws SQLTransientConnectionException {
            throw new SQLTransientConnectionException("synthetic pool timeout");
        }
        @GetMapping("/jooq") Object jooq() {
            throw new org.jooq.exception.DataAccessException("synthetic wrapper",
                    new SQLTransientConnectionException("synthetic pool timeout"));
        }
        @GetMapping("/async") Object async() {
            throw new CompletionException(new CannotGetJdbcConnectionException("synthetic wrapper",
                    new SQLTransientConnectionException("synthetic pool timeout")));
        }
        @GetMapping("/other") Object other() { throw new IllegalStateException("unrelated"); }
    }

    @Test void scheduleReadTimeoutReachesTheSharedHandler() throws Exception {
        var service = mock(com.securityexpert.nexus.ui2.service.failover.FailoverScheduleService.class);
        var ledger = mock(com.securityexpert.nexus.ui2.service.failover.FailoverScheduleLedger.class);
        when(service.getSchedulesForCluster("CLS-TEST-01")).thenThrow(new org.jooq.exception.DataAccessException(
                "synthetic wrapper", new SQLTransientConnectionException("synthetic pool timeout")));
        var mvc = MockMvcBuilders.standaloneSetup(new FailoverScheduleController(service, ledger))
                .setControllerAdvice(new DatabaseBusyAdvice()).build();
        mvc.perform(get("/api/v2/failover/CLS-TEST-01/schedules"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":\"DATABASE_BUSY\",\"message\":\"database busy\"}"));
        verify(service).getSchedulesForCluster("CLS-TEST-01");
        verifyNoInteractions(ledger);
    }

    @Test void disabledScheduleRouteReturnsDenialWithoutReachingServices() throws Exception {
        var service = mock(com.securityexpert.nexus.ui2.service.failover.FailoverScheduleService.class);
        var ledger = mock(com.securityexpert.nexus.ui2.service.failover.FailoverScheduleLedger.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FailoverScheduleController(service, ledger))
                .setControllerAdvice(new DatabaseBusyAdvice()).build();
        mvc.perform(post("/api/v2/failover/CLS-TEST-01/schedules")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"actionKind":"CONTROLLED_FAILOVER","windowStart":"2026-10-03T00:00:00Z",
                         "windowEnd":"2026-10-03T01:00:00Z","maxStartDelayMinutes":5,
                         "requesterId":"synthetic_requester","approverId":"synthetic_approver",
                         "reason":"synthetic","clientNonce":"synthetic_nonce"}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("GENERIC_FAILOVER_EXECUTION_DISABLED"));
        verifyNoInteractions(service, ledger);
    }

    @Test void directAndWrappedTimeoutsReturnSanitized503() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new DatabaseBusyAdvice()).build();
        for (String path : new String[] {"/direct", "/jooq", "/async"}) {
            mvc.perform(get(path)).andExpect(status().isServiceUnavailable())
                    .andExpect(content().json("{\"error\":\"DATABASE_BUSY\",\"message\":\"database busy\"}"));
        }
        assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(get("/other")));
    }
}
