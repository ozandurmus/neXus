package com.securityexpert.nexus.ui2.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class BackupDownloadTicketTest {

    private static final String ARTEFACT = "c7e8b1a8-aa0e-408b-8f78-f45f09b0b735";

    private static MockHttpServletRequest as(String actor) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, actor);
        return r;
    }

    @Test
    void aTicketNeedsAReasonAndIsSingleUseForItsOwnActor() {
        BackupController c = new BackupController(null, null, null, null);
        assertThat(c.downloadTicket(ARTEFACT, new BackupController.DownloadRequest("short"), as("actor-a")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = c.downloadTicket(ARTEFACT, new BackupController.DownloadRequest("audit evidence for MDS"), as("actor-a")).getBody();
        String ticket = (String) body.get("ticket");
        assertThat((String) body.get("href")).isEqualTo("/backups/" + ARTEFACT + "/download?ticket=" + ticket);
        // another actor cannot use it -- and the attempt consumes it
        assertThat(c.downloadWithTicket(ARTEFACT, ticket, as("actor-b")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(c.downloadWithTicket(ARTEFACT, ticket, as("actor-a")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // a fresh ticket for the right actor passes the ticket check (the download service is absent here: 503)
        String fresh = (String) c.downloadTicket(ARTEFACT, new BackupController.DownloadRequest("audit evidence for MDS"), as("actor-a")).getBody().get("ticket");
        assertThat(c.downloadWithTicket(ARTEFACT, fresh, as("actor-a")).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
