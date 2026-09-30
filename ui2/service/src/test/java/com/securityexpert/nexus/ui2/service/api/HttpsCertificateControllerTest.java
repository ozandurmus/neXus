package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.*;

class HttpsCertificateControllerTest {
    @Test void acceptanceAndStrictRoutesRequireSecurityAdmin() {
        var actions = new ActionRegistry();
        assertEquals(Optional.of(RoleToken.SECURITY_ADMIN), actions.find(ActionRegistry.HTTPS_CERTIFICATE_ACCEPT).orElseThrow().requiredRoleToken());
        assertEquals(Optional.of(RoleToken.SECURITY_ADMIN), actions.find(ActionRegistry.HTTPS_CERTIFICATE_STRICT).orElseThrow().requiredRoleToken());
        assertEquals(ActionRegistry.HTTPS_CERTIFICATE_ACCEPT, SecurityWebMvcConfigTestAccess.actionIdFor("POST /devices/*/https-certificate/accept"));
        assertEquals(ActionRegistry.HTTPS_CERTIFICATE_STRICT, SecurityWebMvcConfigTestAccess.actionIdFor("PUT /devices/*/https-certificate/strict"));
        assertEquals(ActionRegistry.DEVICE_READ, SecurityWebMvcConfigTestAccess.actionIdFor("GET /devices/*/https-certificate"));
    }

    @Test void readUsesServerRbacAffordancesAndFlagsAnEnrolledEndpoint() {
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            var create = DSL.using(SQLDialect.POSTGRES);
            var rows = ctx.sql().startsWith("SELECT e.address_ref")
                    ? create.fetchFromStringData(new String[][]{{"address_ref", "https_certificate_strict", "default_port"},
                        {"192.0.2.10:8443", "false", "443"}})
                    : create.fetchFromStringData(new String[][]{{"trust_entry_id", "fingerprint_sha256", "subject_cn", "issuer_cn", "not_after", "status"},
                        {"pending-1", "a".repeat(64), "FW-TANGO-04", "Synthetic issuer", "2030-01-01 00:00:00", "PENDING"}});
            return new MockResult[]{new MockResult(rows.size(), rows)};
        }), SQLDialect.POSTGRES);
        TransactionBoundary tx = new TransactionBoundary() {
            public <T> T inTransaction(Function<DSLContext,T> work) { return work.apply(dsl); }
        };
        var rbac = mock(RbacEvaluator.class);
        var controller = new HttpsCertificateController(tx, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor");
        request.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "synthetic-session");
        for (var outcome : new AuthzOutcome[]{AuthzOutcome.DENIED, AuthzOutcome.PERMITTED}) {
            when(rbac.evaluate(anyString(), any(), any())).thenReturn(new RbacEvaluator.Decision(outcome, Optional.empty(), Optional.empty(), Optional.empty()));
            var body = (Map<?,?>) controller.read("device-1", request).getBody();
            assertEquals(outcome == AuthzOutcome.PERMITTED, body.get("can_accept"));
            assertEquals(outcome == AuthzOutcome.PERMITTED, body.get("can_set_strict"));
            assertEquals(true, body.get("certificate_changed"));
            assertFalse(body.toString().contains("192.0.2.10"));
        }
    }
}
