package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.failover.FailoverScheduleLedger;
import com.securityexpert.nexus.ui2.service.failover.FailoverScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailoverScheduleControllerTest {
    @Test void genericSchedulingAndTriggerNeverReachServices() {
        var service=mock(FailoverScheduleService.class);
        var ledger=mock(FailoverScheduleLedger.class);
        var controller=new FailoverScheduleController(service,ledger);
        assertEquals(HttpStatus.FORBIDDEN,controller.createSchedule("synthetic-unit",null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,controller.triggerScheduleExecution("synthetic-schedule","synthetic-actor").getStatusCode());
        verifyNoInteractions(service,ledger);
    }
}
