package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.projectplan.ProjectPlanReader;

/**
 * {@code GET /project-plan} (WORKER.md movement NXS-LOCAL-0174): the
 * Administration screen's "Project plan" tab, backed by a real read of the
 * repository's own {@code project/} JSON sources instead of the earlier
 * product's build-time static inline. PO-NAV-5: open to any authenticated
 * session today; administration-only once real directory-backed
 * authorization exists -- not simulated here.
 */
@RestController
public final class ProjectPlanController {

    private final ProjectPlanReader projectPlanReader;

    public ProjectPlanController(ProjectPlanReader projectPlanReader) {
        this.projectPlanReader = projectPlanReader;
    }

    @GetMapping("/project-plan")
    public ResponseEntity<Map<String, Object>> getProjectPlan() {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(projectPlanReader.read());
    }
}
