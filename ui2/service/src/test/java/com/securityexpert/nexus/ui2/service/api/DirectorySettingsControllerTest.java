package com.securityexpert.nexus.ui2.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.securityexpert.nexus.ui2.persistence.identity.DirectoryProfileRepository;
import com.securityexpert.nexus.ui2.service.security.GateChain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DirectorySettingsController.class)
public class DirectorySettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DirectoryProfileRepository repository;

    @MockBean
    private GateChain gateChain;

    @Test
    public void testGetLdapConfig() throws Exception {
        mockMvc.perform(get("/api/v2/config/ldap"))
               .andExpect(status().isOk());
    }
}
