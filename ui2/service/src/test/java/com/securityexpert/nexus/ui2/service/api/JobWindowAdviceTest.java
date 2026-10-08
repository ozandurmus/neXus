package com.securityexpert.nexus.ui2.service.api;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobWindowAdviceTest {
    @RestController
    static class Submission {
        @PostMapping("/synthetic-job") public void submit() {
            new JobWindowPolicy(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC), 60).requireOpen();
        }
    }
    @Test void manualRefusalHasSafeMessageCodeAndZonedNextStart() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Submission()).setControllerAdvice(new JobWindowAdvice()).build();
        mvc.perform(post("/synthetic-job"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OUTSIDE_JOB_WINDOW"))
            .andExpect(jsonPath("$.next_window_start").value("2026-10-08T18:00+03:00[Europe/Istanbul]"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Device jobs are available")));
    }
}
