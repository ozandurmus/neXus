package com.securityexpert.nexus.ui2.service.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.jobs.JobLogQueryService;
import com.securityexpert.nexus.ui2.service.jobs.JobLogQueryService.JobEvent;

@RestController
public final class JobLogController {

    private final JobLogQueryService jobLogQueryService;

    public JobLogController(JobLogQueryService jobLogQueryService) {
        this.jobLogQueryService = jobLogQueryService;
    }

    @GetMapping("/api/v2/jobs")
    public List<JobEvent> listJobs() {
        return jobLogQueryService.recent();
    }
}
