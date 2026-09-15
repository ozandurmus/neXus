package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.audit.AuditLogRepository;
import com.securityexpert.nexus.ui2.persistence.audit.JooqAuditLogRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqAuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.AuditController;
import com.securityexpert.nexus.ui2.service.audit.AuditService;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChain;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.security.SessionHasher;

/**
 * `UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md` §8 tests 1, 6, 7, 8, 9 and 10,
 * proved end to end against a real PostgreSQL 16 server through the real
 * {@link AuditController} / {@link AuditService} / {@link
 * JooqAuditLogRepository} / {@link GateChain} / {@link RbacEvaluator} stack
 * -- the same production classes {@code AuditConfiguration}/{@code
 * RbacConfiguration} wire, constructed here by hand instead of through
 * Spring (this module runs no Spring context; see {@code
 * SecurityWebMvcConfig}'s own note that this movement is exercised by
 * direct method calls, never a running server).
 *
 * <p>Like every other class in this package/module, this test needs a real
 * PostgreSQL 16 carrier ({@code UI2_TEST_JDBC_URL} or a container runtime)
 * that this development environment does not have; it must compile and is
 * the real-database half of {@code AuditServiceTest}/{@code
 * AuditControllerTest}'s (service module) in-memory-fake proofs of the same
 * claims -- see those classes' Javadoc, and {@code
 * FirstBootIdentitySeedingIntegrationTest}'s Javadoc for the same standing
 * note on this environment.</p>
 */
class AuditScreenAuthorizationIntegrationTest {

    private static final String VIEWER_GROUP = "cn=audit-viewers,dc=test";
    private static final String SECURITY_ADMIN_GROUP = "cn=audit-security-admins,dc=test";

    private static Ui2PostgresFixture fixture;
    private static TransactionBoundary transactionBoundary;
    private static SessionRepository sessionRepository;
    private static RoleBindingRepository roleBindingRepository;
    private static ActorAuthzStateRepository actorAuthzStateRepository;
    private static AuthzDecisionRepository authzDecisionRepository;
    private static AuditLogRepository auditLogRepository;
    private static GroupReferenceCipher cipher;
    private static String credentialReferenceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_screen_authz");
        transactionBoundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        sessionRepository = new JooqSessionRepository(transactionBoundary);
        roleBindingRepository = new JooqRoleBindingRepository(transactionBoundary);
        actorAuthzStateRepository = new JooqActorAuthzStateRepository(transactionBoundary);
        authzDecisionRepository = new JooqAuthzDecisionRepository(transactionBoundary);
        auditLogRepository = new JooqAuditLogRepository(transactionBoundary);
        cipher = testCipher();

        roleBindingRepository.create(OpaqueId.random().value(), RoleToken.VIEWER.token(),
                cipher.encrypt(VIEWER_GROUP), "test-key", "bootstrap", "harness.rbac.setup");
        roleBindingRepository.create(OpaqueId.random().value(), RoleToken.SECURITY_ADMIN.token(),
                cipher.encrypt(SECURITY_ADMIN_GROUP), "test-key", "bootstrap", "harness.rbac.setup");

        try (Connection app = fixture.appConnection()) {
            credentialReferenceId = Ui2Rows.insertCredentialReference(app);
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    /** Contract §8 test 6 ({@code AuditScreenViewerSeesOnlyOwnRowsTest}'s list half). */
    @Test
    void viewerSeesOnlyOwnRowsThroughTheRealDatabase() throws SQLException {
        String viewerActor = seedActor(Set.of(VIEWER_GROUP));
        String otherActor = seedActor(Set.of());
        DeviceRepository devices = new JooqDeviceRepository(transactionBoundary);
        String ownDeviceId = registerDevice(devices, viewerActor);
        registerDevice(devices, otherActor);

        AuditController controller = controller();
        ResponseEntity<Map<String, Object>> response = controller.list(null, null, null, null, null, null, null,
                null, null, null, sessionRequest(viewerActor));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) response.getBody().get("entries");
        assertTrue(entries.stream().allMatch(e -> viewerActor.equals(e.get("actor_fingerprint"))),
                "role:viewer must never see another actor's row");
        assertTrue(entries.stream().anyMatch(e -> ownDeviceId.equals(e.get("row_pk"))),
                "role:viewer must see its own row");
    }

    /** Contract §8 test 7 ({@code AuditScreenReadOwnNeverWidensTest}). */
    @Test
    void readOwnActorFingerprintFilterIsRefusedNotSilentlyNarrowed() throws SQLException {
        String viewerActor = seedActor(Set.of(VIEWER_GROUP));
        String otherActor = seedActor(Set.of());

        AuditController controller = controller();
        ResponseEntity<Map<String, Object>> response = controller.list(null, null, null, otherActor, null, null,
                null, null, null, null, sessionRequest(viewerActor));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("actor_not_in_required_group", response.getBody().get("reason_code"));
    }

    /** Contract §8 test 8 ({@code AuditScreenRefusalRecordedInAuthzDecisionsTest}), both directions. */
    @Test
    void refusalWritesExactlyOneAuthzDecisionsRowAndZeroAuditLogRows() throws SQLException {
        String neitherActor = seedActor(Set.of());

        long decisionsBefore = countAuthzDecisions(neitherActor);
        long auditRowsBefore = countAuditLogRowsForActor(neitherActor);

        AuditController controller = controller();
        ResponseEntity<Map<String, Object>> response = controller.list(null, null, null, null, null, null, null,
                null, null, null, sessionRequest(neitherActor));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(decisionsBefore + 1, countAuthzDecisions(neitherActor),
                "exactly one authz_decisions row for this refused read");
        assertEquals(auditRowsBefore, countAuditLogRowsForActor(neitherActor),
                "a refused read must never write an audit_log row -- ui2_app cannot even if it tried");
    }

    /** Contract §8 test 9 ({@code AuditScreenQueryIsBoundedTest}), the keyset-pagination half against real SQL. */
    @Test
    void keysetPaginationWalksEveryRowExactlyOnceThroughRealSql() throws SQLException {
        String adminActor = seedActor(Set.of(SECURITY_ADMIN_GROUP));
        DeviceRepository devices = new JooqDeviceRepository(transactionBoundary);
        String d1 = registerDevice(devices, adminActor);
        String d2 = registerDevice(devices, adminActor);
        String d3 = registerDevice(devices, adminActor);

        AuditController controller = controller();
        java.util.Set<String> seenRowPks = new java.util.HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            ResponseEntity<Map<String, Object>> response = controller.list("devices", null, null, null, null, null,
                    null, null, "1", cursor, sessionRequest(adminActor));
            assertEquals(HttpStatus.OK, response.getStatusCode());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) response.getBody().get("entries");
            assertTrue(entries.size() <= 1, "limit=1 must never return more than one entry");
            entries.forEach(e -> seenRowPks.add((String) e.get("row_pk")));
            Object nextCursor = response.getBody().get("next_cursor");
            pages++;
            assertTrue(pages < 100, "pagination must terminate -- a bug here would loop forever, never assert");
            if (nextCursor == null) {
                break;
            }
            cursor = (String) nextCursor;
        }

        assertTrue(seenRowPks.containsAll(List.of(d1, d2, d3)),
                "walking every page with limit=1 must reach every one of this actor's rows exactly once");
    }

    /** Contract §8 test 10 ({@code AuditScreenHasNoWritePathTest}), the live-grant half (the source-absence half is proved by grep in this same class). */
    @Test
    void ui2AppIsRefusedAtTheDatabaseForAnyAuditTableWrite() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            for (String sql : List.of(
                    "INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id) "
                            + "VALUES ('devices', 'x', 'INSERT', 'x', 'x')",
                    "DELETE FROM audit_log",
                    "INSERT INTO audit_redaction_policy VALUES ('devices', 'device_id', 1, 'x')")) {
                SQLException thrown = assertThrows(SQLException.class, () -> {
                    try (Statement statement = app.createStatement()) {
                        statement.execute(sql);
                    }
                });
                assertEquals("42501", thrown.getSQLState(),
                        "ui2_app must be refused insufficient_privilege, not merely fail for another reason");
            }
        }
    }

    /** Contract §8 test 1 ({@code AuditScreenRedactedValueNeverReachesPayloadTest}), end to end through the real stack. */
    @Test
    void redactedSessionSecretNeverReachesTheDetailPayload()
            throws SQLException, com.fasterxml.jackson.core.JsonProcessingException {
        String adminActor = seedActor(Set.of(SECURITY_ADMIN_GROUP));
        String csrfSecret = "synthetic-secret-" + OpaqueId.random().value();
        long auditId = insertAuditedSessionAndReturnAuditId(csrfSecret);

        AuditController controller = controller();
        ResponseEntity<Map<String, Object>> response =
                controller.get(String.valueOf(auditId), sessionRequest(adminActor));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response.getBody());
        assertFalse(serialized.contains(csrfSecret), "the raw secret must never reach the serialized response");
        assertFalse(serialized.contains("sha256:"), "the digest itself must never reach the response either (§3.2)");
        @SuppressWarnings("unchecked")
        Map<String, Object> afterFields = (Map<String, Object>) response.getBody().get("after_fields");
        @SuppressWarnings("unchecked")
        Map<String, Object> csrfProjection = (Map<String, Object>) afterFields.get("csrf_secret");
        assertEquals("REDACTED", csrfProjection.get("state"));
        assertNotNull(csrfProjection.get("reason"), "a REDACTED field must carry the policy's own reason");
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static AuditController controller() {
        RbacEvaluator rbacEvaluator = new RbacEvaluator(roleBindingRepository, actorAuthzStateRepository, cipher);
        GateChain gateChain = new GateChain(sessionRepository, new ActionRegistry(), rbacEvaluator,
                authzDecisionRepository);
        AuditService auditService = new AuditService(auditLogRepository);
        return new AuditController(auditService, gateChain, rbacEvaluator);
    }

    /** Creates a real {@code sessions} row and resolves the actor's real (possibly empty) group membership. */
    private static String seedActor(java.util.Set<String> groupReferences) {
        String actorFingerprint = "audit-actor-" + OpaqueId.random().value();
        String rawCookie = "raw-cookie-" + OpaqueId.random().value();
        sessionRepository.createActive(SessionHasher.hash(rawCookie), actorFingerprint, "csrf-secret", Instant.now(),
                Duration.ofMinutes(15), Duration.ofHours(8), "harness.login");
        actorAuthzStateRepository.upsert(actorFingerprint, groupReferences, Instant.now(),
                Instant.now().plus(Duration.ofMinutes(15)));
        rawCookieByActor.put(actorFingerprint, rawCookie);
        return actorFingerprint;
    }

    private static final Map<String, String> rawCookieByActor = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A minimal {@link HttpServletRequest} for the four methods {@code
     * AuditController}/{@code GateChainInterceptor.toGateRequest} actually
     * call ({@code getMethod}, {@code getCookies}, {@code getHeader},
     * {@code getParameter}) -- a hand-rolled proxy rather than a new
     * {@code spring-test} dependency this module does not already have.
     */
    private static HttpServletRequest sessionRequest(String actorFingerprint) {
        Cookie[] cookies = {new Cookie("ui2_session", rawCookieByActor.get(actorFingerprint))};
        return (HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                AuditScreenAuthorizationIntegrationTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> "GET";
                    case "getCookies" -> cookies;
                    case "getHeader", "getParameter" -> null;
                    default -> throw new UnsupportedOperationException(
                            "not used by AuditController: " + method.getName());
                });
    }

    private static String registerDevice(DeviceRepository devices, String actorFingerprint) {
        String deviceId = Ui2Rows.opaqueId("dev-audit");
        String endpointId = Ui2Rows.opaqueId("ep-audit");
        devices.registerDraft(new DeviceDraft(deviceId, "gateway", "harness-vendor", "manual", true,
                credentialReferenceId, endpointId, "test_transport", "address-1"), actorFingerprint,
                "device_register");
        return deviceId;
    }

    private static long countAuthzDecisions(String actorFingerprint) throws SQLException {
        try (Connection app = fixture.appConnection()) {
            return Ui2Rows.count(app,
                    "SELECT count(*) FROM authz_decisions WHERE actor_fingerprint = '" + actorFingerprint + "'");
        }
    }

    private static long countAuditLogRowsForActor(String actorFingerprint) throws SQLException {
        try (Connection app = fixture.appConnection()) {
            return Ui2Rows.count(app,
                    "SELECT count(*) FROM audit_log WHERE actor_fingerprint = '" + actorFingerprint + "'");
        }
    }

    private static long insertAuditedSessionAndReturnAuditId(String csrfSecret) throws SQLException {
        String sessionId = Ui2Rows.opaqueId("sess-audit");
        Instant now = Instant.now();
        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            try {
                Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.audit.session");
                try (Statement statement = app.createStatement()) {
                    statement.execute("INSERT INTO sessions(session_id, actor_fingerprint, csrf_secret, state, "
                            + "idle_deadline_at, absolute_expires_at) VALUES ('" + sessionId + "', '"
                            + Ui2Rows.ACTOR + "', '" + csrfSecret + "', 'ACTIVE', '"
                            + now.plusSeconds(900) + "', '" + now.plusSeconds(28800) + "')");
                }
                app.commit();
            } catch (SQLException e) {
                app.rollback();
                throw e;
            } finally {
                app.setAutoCommit(true);
            }
            return AuditProjectionTestSupport.auditIdOf(app, "sessions", sessionId, 1);
        }
    }

    private static GroupReferenceCipher testCipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }
}
