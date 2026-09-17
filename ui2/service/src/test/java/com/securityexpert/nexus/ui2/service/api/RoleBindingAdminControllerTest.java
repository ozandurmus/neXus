package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class RoleBindingAdminControllerTest {

    @Test
    void createRequestAcceptsTheFrontendSnakeCaseRoleBindingFields() throws Exception {
        var request = new ObjectMapper().readValue("""
                {"role_token":"role:security_admin","local_identity_id":"local-user-id"}
                """, RoleBindingAdminController.CreateRequest.class);

        assertEquals("role:security_admin", request.roleToken());
        assertEquals("local-user-id", request.localIdentityId());
    }
}
