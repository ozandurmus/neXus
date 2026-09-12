package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqAuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.device.ActionAffordanceView;
import com.securityexpert.nexus.ui2.service.device.DeviceWorkspaceAffordanceEvaluator;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Contract §6.1/§6.3 and the task's RBAC hard rule, proved at the
 * read-model layer with the real {@code RbacEvaluator} and real
 * {@code authz_decisions} rows -- no controller and no HTTP {@code 403}
 * exist in this task's scope, so tests 5 ({@code GetAffordanceAndPostRefusalAgree})
 * and the full end-to-end "visible but refused on a real screen" (§6.2)
 * remain unproven at this layer; see the task report.
 *
 * <p>Proves: test 3's core assertion ({@code RefusedActionIsRecordedNotMerelyDisplayed})
 * -- a refusal renders {@code DENIED}/{@code actor_not_in_required_group}
 * only when a matching {@code authz_decisions} row exists agreeing with it
 * -- and test 4's key-set invariant
 * ({@code RefusedAffordanceStillRendersInPayload}) over the one
 * device-adjacent action {@link ActionRegistry} currently seeds
 * ({@link ActionRegistry#DEVICE_REGISTER}; see the evaluator's own javadoc
 * for why no per-device action id is invented here).</p>
 */
class DeviceWorkspaceAffordanceEvaluatorTest {

    private static Ui2PostgresFixture fixture;
    private static RoleBindingRepository roleBindingRepository;
    private static ActorAuthzStateRepository actorAuthzStateRepository;
    private static AuthzDecisionRepository authzDecisionRepository;
    private static SessionRepository sessionRepository;
    private static GroupReferenceCipher cipher;
    private static DeviceWorkspaceAffordanceEvaluator evaluator;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("device_workspace_affordance");
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(
                DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        roleBindingRepository = new JooqRoleBindingRepository(transactionBoundary);
        actorAuthzStateRepository = new JooqActorAuthzStateRepository(transactionBoundary);
        authzDecisionRepository = new JooqAuthzDecisionRepository(transactionBoundary);
        sessionRepository = new JooqSessionRepository(transactionBoundary);
        cipher = DeviceWorkspaceTestRows.newCipher();
        RbacEvaluator rbacEvaluator = new RbacEvaluator(roleBindingRepository, actorAuthzStateRepository, cipher);
        evaluator = new DeviceWorkspaceAffordanceEvaluator(new ActionRegistry(), rbacEvaluator,
                authzDecisionRepository);
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aRefusedActionIsRecordedInAuthzDecisionsAgreeingWithTheRenderedOutcome() throws SQLException {
        String actor = "actor-" + DeviceWorkspaceTestRows.opaqueId("no-group-match");
        String targetDeviceId = DeviceWorkspaceTestRows.opaqueId("dev-target");
        Instant now = DeviceWorkspaceTestRows.now();

        // A binding exists for the required token, but this actor's
        // resolved group set does not contain the bound group -- DENIED,
        // never AUTHZ_NOT_EVALUATED, never a silent permit.
        roleBindingRepository.create(DeviceWorkspaceTestRows.opaqueId("binding"), RoleToken.ONBOARDING_ADMIN.token(),
                cipher.encrypt("cn=onboarding-admins,dc=harness"), "harness-key", "harness-admin",
                "harness.seed.binding");
        actorAuthzStateRepository.upsert(actor, java.util.Set.of("cn=some-other-group,dc=harness"), now,
                now.plus(Duration.ofHours(1)));
        SessionRecord session = sessionRepository.createActive(DeviceWorkspaceTestRows.opaqueId("session"), actor,
                "harness-csrf", now, Duration.ofHours(1), Duration.ofDays(1), "harness.seed.session");

        Map<String, ActionAffordanceView> affordance = evaluator.evaluate(List.of(ActionRegistry.DEVICE_REGISTER),
                session.sessionId(), actor, targetDeviceId, now);

        ActionAffordanceView rendered = affordance.get(ActionRegistry.DEVICE_REGISTER);
        assertEquals(AuthzOutcome.DENIED, rendered.outcome());
        assertEquals(Optional.of(RbacEvaluator.REASON_ACTOR_NOT_IN_REQUIRED_GROUP), rendered.reasonCode());

        // The matching authz_decisions row -- test 3's own assertion: a
        // rendered label with no row, or a disagreeing row, must fail.
        try (Connection app = fixture.appConnection();
                PreparedStatement statement = app.prepareStatement(
                        "select outcome, reason_code, target_ref from authz_decisions "
                                + "where actor_fingerprint = ? and action_id = ? order by decision_id desc limit 1")) {
            statement.setString(1, actor);
            statement.setString(2, ActionRegistry.DEVICE_REGISTER);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "no authz_decisions row was written for this evaluation");
                assertEquals(rendered.outcome().name(), rows.getString("outcome"));
                assertEquals(rendered.reasonCode().orElseThrow(), rows.getString("reason_code"));
                assertEquals(targetDeviceId, rows.getString("target_ref"));
            }
        }
    }

    @Test
    void theAffordanceKeySetDoesNotVaryByActor() {
        String noRoleActor = "actor-" + DeviceWorkspaceTestRows.opaqueId("no-role");
        String securityAdminActor = "actor-" + DeviceWorkspaceTestRows.opaqueId("security-admin");
        Instant now = DeviceWorkspaceTestRows.now();
        String targetDeviceId = DeviceWorkspaceTestRows.opaqueId("dev-target");

        // security_admin has no binding for role:onboarding_admin -- refused
        // the same way as the no-role actor, never granted by adjacency
        // (ActionRegistry javadoc, C3 §4.1 separation of duties).
        roleBindingRepository.create(DeviceWorkspaceTestRows.opaqueId("binding"), RoleToken.SECURITY_ADMIN.token(),
                cipher.encrypt("cn=security-admins,dc=harness"), "harness-key", "harness-admin",
                "harness.seed.binding");
        actorAuthzStateRepository.upsert(securityAdminActor, java.util.Set.of("cn=security-admins,dc=harness"), now,
                now.plus(Duration.ofHours(1)));
        SessionRecord adminSession = sessionRepository.createActive(DeviceWorkspaceTestRows.opaqueId("session"),
                securityAdminActor, "harness-csrf", now, Duration.ofHours(1), Duration.ofDays(1),
                "harness.seed.session");
        SessionRecord noRoleSession = sessionRepository.createActive(DeviceWorkspaceTestRows.opaqueId("session"),
                noRoleActor, "harness-csrf", now, Duration.ofHours(1), Duration.ofDays(1), "harness.seed.session");

        Map<String, ActionAffordanceView> noRoleAffordance = evaluator.evaluate(List.of(ActionRegistry.DEVICE_REGISTER),
                noRoleSession.sessionId(), noRoleActor, targetDeviceId, now);
        Map<String, ActionAffordanceView> adminAffordance = evaluator.evaluate(List.of(ActionRegistry.DEVICE_REGISTER),
                adminSession.sessionId(), securityAdminActor, targetDeviceId, now);

        assertEquals(noRoleAffordance.keySet(), adminAffordance.keySet());
        assertTrue(noRoleAffordance.get(ActionRegistry.DEVICE_REGISTER) != null);
        assertTrue(adminAffordance.get(ActionRegistry.DEVICE_REGISTER) != null);
        // security_admin is refused too (no onboarding_admin binding covers it) -- role_token_unbound
        // because THIS test seeds no active role:onboarding_admin binding at all.
        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, adminAffordance.get(ActionRegistry.DEVICE_REGISTER).outcome());
        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, noRoleAffordance.get(ActionRegistry.DEVICE_REGISTER).outcome());
    }
}
