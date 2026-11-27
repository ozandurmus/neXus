package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.securityexpert.nexus.ui2.service.projectplan.ProjectPlanReader;

/** WORKER.md AC-1: {@code GET /project-plan} returns the reader's own envelope, unconditionally 200. */
class ProjectPlanControllerTest {

    private static Path fixture() {
        try {
            URL url = ProjectPlanControllerTest.class.getResource("/projectplan/clean/project");
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getProjectPlanReturns200WithTheReaderEnvelope() {
        ProjectPlanController controller = new ProjectPlanController(new ProjectPlanReader(fixture()));

        ResponseEntity<Map<String, Object>> response = controller.getProjectPlan();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = response.getBody();
        assertEquals("1.0", body.get("schema_version"));
        assertEquals(80.0, (double) body.get("current_track_progress_percent"));
    }
}
