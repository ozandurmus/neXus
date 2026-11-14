package com.securityexpert.nexus.ui2.service.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.service.discovery.DiscoveryRunService;
import com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointPanCertTrustService;
import com.securityexpert.nexus.ui2.service.discovery.ManagementEndpointSshTrustService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class DiscoveryControllerStartTest {

    private static final String ACTOR = "actor-1";
    private static final String SYNTHETIC_ADDRESS = "192.0.2.10";
    private static final String VENDOR = "check_point";
    private static final String CREDENTIAL_ID = "cred-1";

    private DiscoveryRunService discoveryRunService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        discoveryRunService = mock(DiscoveryRunService.class);
        DiscoveryController controller = new DiscoveryController(
                discoveryRunService,
                mock(ManagementEndpointSshTrustService.class),
                mock(ManagementEndpointPanCertTrustService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void emptyJsonBodyAnswers400ValidationFailed() throws Exception {
        mvc.perform(post("/discovery/runs")
                        .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.reason_code").value("invalid_request"))
                .andExpect(jsonPath("$.reason").isString());

        verifyNoInteractions(discoveryRunService);
    }

    @Test
    void noBodyAtAllAnswers400ValidationFailed() throws Exception {
        mvc.perform(post("/discovery/runs")
                        .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.reason_code").value("invalid_request"))
                .andExpect(jsonPath("$.reason").isString());

        mvc.perform(post("/discovery/runs")
                        .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.reason_code").value("invalid_request"))
                .andExpect(jsonPath("$.reason").isString());

        verifyNoInteractions(discoveryRunService);
    }

    @Test
    void missingOrBlankRequiredFieldsAnswer400ValidationFailed() throws Exception {
        for (String body : List.of(
                "{\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":null,\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":\"\",\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":\"   \",\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}")) {
            mvc.perform(post("/discovery/runs")
                            .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.reason_code").value(DiscoveryRunService.REASON_ADDRESS_INVALID))
                    .andExpect(jsonPath("$.reason").isString());
        }

        for (String body : List.of(
                "{\"management_address\":\"192.0.2.10\",\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":null,\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"\",\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"   \",\"credential_reference_id\":\"cred-1\"}")) {
            mvc.perform(post("/discovery/runs")
                            .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.reason_code").value(DiscoveryRunService.REASON_VENDOR_INVALID))
                    .andExpect(jsonPath("$.reason").isString());
        }

        for (String body : List.of(
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\"}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":null}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":\"\"}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":\"   \"}")) {
            mvc.perform(post("/discovery/runs")
                            .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.reason_code").value(DiscoveryRunService.REASON_CREDENTIAL_REFERENCE_NOT_FOUND))
                    .andExpect(jsonPath("$.reason").isString());
        }

        verifyNoInteractions(discoveryRunService);
    }

    @Test
    void wrongTypesAndMalformedJsonAnswer400ValidationFailed() throws Exception {
        for (String body : List.of(
                "{\"management_address\":123,\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":true,\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":[\"192.0.2.10\"],\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":{\"ip\":\"192.0.2.10\"},\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}")) {
            mvc.perform(post("/discovery/runs")
                            .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.reason_code").value(DiscoveryRunService.REASON_ADDRESS_INVALID))
                    .andExpect(jsonPath("$.reason").isString());
        }

        for (String body : List.of(
                "{\"management_address\":\"192.0.2.10\",\"vendor\":42,\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":false,\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":[\"check_point\"],\"credential_reference_id\":\"cred-1\"}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":{},\"credential_reference_id\":\"cred-1\"}")) {
            mvc.perform(post("/discovery/runs")
                            .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.reason_code").value(DiscoveryRunService.REASON_VENDOR_INVALID))
                    .andExpect(jsonPath("$.reason").isString());
        }

        for (String body : List.of(
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":99}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":true}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":[\"cred-1\"]}",
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":{}}")) {
            mvc.perform(post("/discovery/runs")
                            .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.reason_code").value(DiscoveryRunService.REASON_CREDENTIAL_REFERENCE_NOT_FOUND))
                    .andExpect(jsonPath("$.reason").isString());
        }

        for (String body : List.of(
                "{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\",\"unexpected\":\"x\"}",
                "{not-json",
                "[]",
                "\"string\"",
                "123")) {
            mvc.perform(post("/discovery/runs")
                            .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.reason_code").value("invalid_request"))
                    .andExpect(jsonPath("$.reason").isString());
        }

        verifyNoInteractions(discoveryRunService);
    }

    @Test
    void validRequestPreservesExistingBehaviour() throws Exception {
        when(discoveryRunService.start(ACTOR, SYNTHETIC_ADDRESS, VENDOR, CREDENTIAL_ID))
                .thenReturn(new DiscoveryRunService.StartOutcome.Admitted("run-1", "job-1"));

        mvc.perform(post("/discovery/runs")
                        .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"management_address\":\"192.0.2.10\",\"vendor\":\"check_point\",\"credential_reference_id\":\"cred-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.run_id").value("run-1"))
                .andExpect(jsonPath("$.job_id").value("job-1"));

        verify(discoveryRunService).start(ACTOR, SYNTHETIC_ADDRESS, VENDOR, CREDENTIAL_ID);
    }
}
