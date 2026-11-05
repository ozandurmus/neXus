package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.notification.NotificationRoute;
import com.securityexpert.nexus.ui2.service.notification.NotificationSettings;
import com.securityexpert.nexus.ui2.service.notification.NotificationSettingsStore;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** V193 {@code notification_identity}: validation, default, storage write and who may change it. */
class NotificationSettingsIdentityTest {

    private static NotificationSettings settings(String identity) {
        List<NotificationRoute> routes = NotificationRoute.TYPES.stream()
                .map(t -> new NotificationRoute(t, false, null, null, null)).toList();
        return new NotificationSettings(false, null, 514, "udp", 16, false, null, 25, false, null, null, false, false,
                null, routes, identity);
    }

    @Test
    void defaultIsMaskedForEveryOlderCallShapeAndForAMissingJsonField() throws Exception {
        assertEquals("masked", new NotificationSettings(false, null, 514, "udp", 16, false, null, 25, false, null, null,
                false, false, null).notificationIdentity());
        assertEquals("masked", settings(null).notificationIdentity());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(settings("masked"))
                .replace(",\"notification_identity\":\"masked\"", "");
        NotificationSettings parsed = new ObjectMapper().findAndRegisterModules().readValue(json, NotificationSettings.class);
        assertEquals("masked", parsed.notificationIdentity());
        assertTrue(new ObjectMapper().findAndRegisterModules().writeValueAsString(settings("raw"))
                .contains("\"notification_identity\":\"raw\""));
    }

    @Test
    void onlyMaskedAndRawAreAccepted() {
        assertEquals(List.of(), settings("masked").problems());
        assertEquals(List.of(), settings("raw").problems());
        assertTrue(settings("RAW").problems().contains("notification identity must be masked or raw"));
        assertTrue(settings("partial").problems().contains("notification identity must be masked or raw"));
    }

    @Test
    void controllerRefusesAnInvalidValueAndSavesAValidOneWithTheActor() {
        NotificationSettingsStore store = mock(NotificationSettingsStore.class);
        when(store.read()).thenReturn(settings("raw"));
        NotificationSettingsController controller = new NotificationSettingsController(store);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-fp");

        ResponseEntity<Object> refused = controller.save(settings("everything"), request);
        assertEquals(400, refused.getStatusCode().value());
        verify(store, never()).save(any(), any());

        ResponseEntity<Object> saved = controller.save(settings("raw"), request);
        assertEquals(200, saved.getStatusCode().value());
        verify(store).save(any(NotificationSettings.class), eq("actor-fp"));
    }

    @Test
    void changingTheSettingIsSecurityAdminOnlyBecauseTheWholeWriteRouteIs() {
        var descriptor = new ActionRegistry().find(ActionRegistry.NOTIFICATION_CONFIG_WRITE).orElseThrow();
        assertEquals(Optional.of(RoleToken.SECURITY_ADMIN), descriptor.requiredRoleToken());
        assertTrue(descriptor.alternativeRoleTokens().isEmpty(), "no other role may write notification settings");
    }

    @Test
    void storeWritesTheColumnInTheAuditedUpdateAndReadsItBack() {
        List<String> sql = new ArrayList<>();
        List<Object[]> bindings = new ArrayList<>();
        TransactionBoundary boundary = new TransactionBoundary() {
            @Override
            public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) {
                return work.apply(DSL.using(new MockConnection(ctx -> {
                    sql.add(ctx.sql());
                    bindings.add(ctx.bindings());
                    return new MockResult[] { new MockResult(1) };
                }), SQLDialect.POSTGRES));
            }
        };
        new NotificationSettingsStore(boundary).save(settings("raw"), "actor-fp");
        String update = sql.stream().filter(s -> s.startsWith("update notification_settings")).findFirst().orElseThrow();
        assertTrue(update.contains("notification_identity ="), update);
        assertTrue(java.util.Arrays.asList(bindings.get(0)).contains("raw"));
        assertTrue(java.util.Arrays.asList(bindings.get(0)).contains("actor-fp"), "updated_by feeds the audit trigger");
    }
}
