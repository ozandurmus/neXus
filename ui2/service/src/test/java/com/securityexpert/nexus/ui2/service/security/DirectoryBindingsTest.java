package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;

/** Container-free approved synthetic posture, through the existing evaluator/admin/login paths. */
class DirectoryBindingsTest {
    static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    static final String PROFILE = "synthetic-profile", PRINCIPAL = "opaque-principal", ACTOR = "synthetic-actor";
    static final String ADMIN = RoleToken.SECURITY_ADMIN.token(), BACKUP = RoleToken.BACKUP_ADMIN.token();

    @FunctionalInterface interface Calls { Object call(Method method, Object[] args) throws Throwable; }
    static <T> T port(Class<T> type, Calls calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return "synthetic-port";
            return calls.call(method, args == null ? new Object[0] : args);
        }));
    }

    static final class Fixture {
        final GroupReferenceCipher cipher;
        final Map<String, RoleBindingRecord> rows = new HashMap<>();
        final Map<String, ActorAuthzStateRecord> states = new HashMap<>();
        final Map<String, SessionRecord> sessionRows = new HashMap<>();
        final Map<String, LocalCredentialRecord> localRows = new HashMap<>();
        final List<String> audit = new ArrayList<>();
        final AtomicBoolean currentTrust = new AtomicBoolean(true);
        Runnable beforeMutation = () -> { };
        DirectoryTargetPort.Target target;
        boolean targetMissing;
        final RoleBindingRepository bindings;
        final ActorAuthzStateRepository actors;
        final SessionRepository sessions;
        final LocalCredentialsRepository locals;
        final RootIdentityRepository root = port(RootIdentityRepository.class, (m, a) -> Optional.empty());

        Fixture() {
            byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
            cipher = GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key), "synthetic-key");
            actors = port(ActorAuthzStateRepository.class, (m, a) -> switch (m.getName()) {
                case "find" -> Optional.ofNullable(states.get(a[0]));
                case "upsertDirectory" -> { var row = (ActorAuthzStateRecord) a[0]; states.put(row.actorFingerprint(), row); yield null; }
                case "delete" -> { states.remove(a[0]); yield null; }
                case "expireDirectory" -> { states.clear(); yield null; }
                default -> throw new UnsupportedOperationException("synthetic actor operation");
            });
            locals = port(LocalCredentialsRepository.class, (m, a) -> switch (m.getName()) {
                case "findById" -> Optional.ofNullable(localRows.get(a[0]));
                case "findAll" -> List.copyOf(localRows.values());
                default -> throw new UnsupportedOperationException("synthetic local operation");
            });
            sessions = port(SessionRepository.class, (m, a) -> switch (m.getName()) {
                case "findBySessionId" -> Optional.ofNullable(sessionRows.get(a[0]));
                case "findActiveByActor" -> sessionRows.values().stream().filter(s -> s.actorFingerprint().equals(a[0]) && s.isActive(NOW)).findFirst();
                case "createActive" -> {
                    var row = session((String) a[0], (String) a[1]); sessionRows.put(row.sessionId(), row); yield row;
                }
                case "takeover" -> {
                    sessionRows.remove(a[0]); var row = session((String) a[1], (String) a[2]); sessionRows.put(row.sessionId(), row); yield row;
                }
                default -> throw new UnsupportedOperationException("synthetic session operation");
            });
            bindings = port(RoleBindingRepository.class, (m, a) -> switch (m.getName()) {
                case "findActiveByToken" -> rows.values().stream().filter(r -> r.roleToken().equals(a[0]) && r.isActive()).toList();
                case "find" -> Optional.ofNullable(rows.get(a[0]));
                case "create" -> {
                    String id = (String) a[0];
                    rows.put(id, new RoleBindingRecord(id, (String) a[1], (byte[]) a[2], (String) a[3], (String) a[4], NOW,
                            Optional.empty(), Optional.empty()));
                    audit.add(a[5] + ":" + id); yield id;
                }
                case "createDirectory" -> {
                    var row = (RoleBindingRecord) a[0]; rows.put(row.bindingId(), row);
                    audit.add(a[1] + ":" + row.bindingId() + ":" + row.bindingKind()); yield row.bindingId();
                }
                case "revoke" -> { rows.remove(a[0]); audit.add(a[2] + ":" + a[0]); yield null; }
                case "directoryMutation" -> {
                    beforeMutation.run();
                    @SuppressWarnings("unchecked") var work = (java.util.function.Function<DirectoryMutationRepositories, Object>) a[0];
                    yield work.apply(new DirectoryMutationRepositories(bindings(), actors, sessions, locals));
                }
                default -> throw new UnsupportedOperationException("synthetic binding operation");
            });
            state(ACTOR, PRINCIPAL, Set.of("opaque-group"), NOW.plusSeconds(300));
            directory("admin-binding", ADMIN, DirectoryBindingKind.DIRECTORY_PRINCIPAL, PRINCIPAL, PROFILE);
            sessionRows.put("acting-session", session("acting-session", ACTOR));
            target = new DirectoryTargetPort.Target(PROFILE, DirectoryBindingKind.DIRECTORY_PRINCIPAL,
                    "opaque-other-principal", NOW.plusSeconds(300), this::publish);
        }
        RoleBindingRepository bindings() { return bindings; }
        boolean publish(Runnable write) { if (!currentTrust.get()) return false; write.run(); return true; }
        SessionRecord session(String id, String actor) {
            return new SessionRecord(id, actor, "synthetic-csrf", SessionState.ACTIVE, NOW, NOW, NOW.plusSeconds(600), NOW.plusSeconds(3600),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        void state(String actor, String principal, Set<String> groups, Instant expiry) {
            states.put(actor, new ActorAuthzStateRecord(actor, groups, NOW, expiry, PROFILE,
                    cipher.encryptDirectory(principal, PROFILE, DirectoryBindingKind.DIRECTORY_PRINCIPAL), cipher.keyId()));
        }
        void directory(String id, String token, DirectoryBindingKind kind, String reference, String profile) {
            rows.put(id, new RoleBindingRecord(id, token, cipher.encryptDirectory(reference, profile, kind), cipher.keyId(), ACTOR,
                    NOW, Optional.empty(), Optional.empty(), kind, profile));
        }
        RbacEvaluator evaluator() {
            return new RbacEvaluator(bindings, actors, cipher, new LocalIdentityResolver(locals), new LocalRoleTokenResolver(bindings, cipher), root);
        }
        RbacEvaluator.Decision backup() { return evaluator().evaluate(ACTOR, Optional.of(RoleToken.BACKUP_ADMIN), NOW); }
        RoleBindingAdminService admin(boolean enabled) {
            DirectoryTargetPort targets = new DirectoryTargetPort() {
                public List<Target> list(String p, DirectoryBindingKind k, Instant now) { return List.of(target); }
                public Optional<Target> resolve(Target selected, Instant now) { return targetMissing ? Optional.empty() : Optional.of(target); }
            };
            return new RoleBindingAdminService(bindings, actors, cipher, new SecurityAdminLockoutGuard(bindings, locals, cipher),
                    root, enabled, sessions, new LocalIdentityResolver(locals), targets);
        }
        String handle(RoleBindingAdminService service) {
            return service.resolveSelections(ACTOR, "acting-session", target.profileId(), target.kind(), NOW).get(0).handle();
        }
        RoleBindingAdminService.Outcome create(RoleBindingAdminService service, String handle, Instant now) {
            return service.createDirectory(ACTOR, "acting-session", BACKUP, handle, target.profileId(), target.kind(), now);
        }
        void local(String id, boolean enabled) {
            localRows.put(id, new LocalCredentialRecord(id, "synthetic-name", new byte[0], new byte[0], "synthetic", 1, 1, 1, 0,
                    Optional.empty(), NOW, NOW, enabled, ACTOR, NOW, false));
            rows.put("local-" + id, new RoleBindingRecord("local-" + id, ADMIN, cipher.encrypt(id), cipher.keyId(), ACTOR, NOW,
                    Optional.empty(), Optional.empty()));
        }
    }

    @Test void groupPrincipalAndMixedOrHaveDeterministicExistingAuditAttribution() {
        Fixture f = new Fixture();
        f.directory("z-group", BACKUP, DirectoryBindingKind.DIRECTORY_GROUP, "opaque-group", PROFILE);
        assertEquals("z-group", f.backup().bindingId().orElseThrow());
        f.directory("a-principal", BACKUP, DirectoryBindingKind.DIRECTORY_PRINCIPAL, PRINCIPAL, PROFILE);
        assertEquals("a-principal", f.backup().bindingId().orElseThrow());
        f.rows.remove("z-group");
        assertEquals(AuthzOutcome.PERMITTED, f.backup().outcome());
        f.rows.remove("a-principal");
        assertEquals(RbacEvaluator.REASON_ROLE_TOKEN_UNBOUND, f.backup().reasonCode().orElseThrow());
    }

    @Test void exactKindProfileProofExpiryAndCorruptionNeverInventGrants() {
        Fixture f = new Fixture();
        f.directory("binding", BACKUP, DirectoryBindingKind.DIRECTORY_GROUP, PRINCIPAL, PROFILE);
        assertEquals(AuthzOutcome.DENIED, f.backup().outcome()); // Principal spelling is not group membership.
        f.directory("binding", BACKUP, DirectoryBindingKind.DIRECTORY_PRINCIPAL, PRINCIPAL, "other-profile");
        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, f.backup().outcome());
        f.directory("binding", BACKUP, DirectoryBindingKind.DIRECTORY_PRINCIPAL, PRINCIPAL.toUpperCase(Locale.ROOT), PROFILE);
        assertEquals(AuthzOutcome.DENIED, f.backup().outcome());
        f.directory("binding", BACKUP, DirectoryBindingKind.DIRECTORY_PRINCIPAL, PRINCIPAL, PROFILE);
        f.state(ACTOR, PRINCIPAL, Set.of(), NOW);
        assertEquals(RbacEvaluator.REASON_ACTOR_GROUP_SET_STALE, f.backup().reasonCode().orElseThrow());
        f.states.put(ACTOR, new ActorAuthzStateRecord(ACTOR, Set.of(PRINCIPAL), NOW, NOW.plusSeconds(300)));
        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, f.backup().outcome());
        f.state(ACTOR, PRINCIPAL, Set.of(), NOW.plusSeconds(300));
        f.rows.get("binding").groupReferenceEncrypted()[0] ^= 1;
        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, f.backup().outcome());
        f.rows.put("binding", new RoleBindingRecord("binding", BACKUP, f.cipher.encrypt(PRINCIPAL), f.cipher.keyId(), ACTOR,
                NOW, Optional.empty(), Optional.empty()));
        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, f.backup().outcome());
        f.directory("proven", BACKUP, DirectoryBindingKind.DIRECTORY_PRINCIPAL, PRINCIPAL, PROFILE);
        assertEquals(AuthzOutcome.PERMITTED, f.backup().outcome()); // Proven OR grant survives unrelated unknown row.
    }

    @Test void typedEnvelopeCannotBeReadAsLegacyGroupOrRelabeledAndLocalNamespacesStayDistinct() {
        Fixture f = new Fixture();
        byte[] wire = f.cipher.encryptDirectory(PRINCIPAL, PROFILE, DirectoryBindingKind.DIRECTORY_PRINCIPAL);
        assertThrows(RuntimeException.class, () -> f.cipher.decrypt(wire));
        assertThrows(RuntimeException.class, () -> f.cipher.decryptDirectory(wire, PROFILE, DirectoryBindingKind.DIRECTORY_GROUP, f.cipher.keyId()));
        assertThrows(RuntimeException.class, () -> f.cipher.decryptDirectory(wire, PROFILE, DirectoryBindingKind.DIRECTORY_PRINCIPAL, "missing-key"));
        f.local(PRINCIPAL, true);
        f.directory("directory-local-spelling", BACKUP, DirectoryBindingKind.DIRECTORY_PRINCIPAL, PRINCIPAL, PROFILE);
        String localActor = LocalMechanism.actorFingerprintFor(PRINCIPAL);
        assertEquals(AuthzOutcome.DENIED, f.evaluator().evaluate(localActor, Optional.of(RoleToken.BACKUP_ADMIN), NOW).outcome());
        f.state(localActor, PRINCIPAL, Set.of(), NOW.plusSeconds(300));
        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, f.evaluator().evaluate(localActor, Optional.of(RoleToken.SECURITY_ADMIN), NOW).outcome());
    }

    @Test void bothKindsRefuseSelfOnCreateAndRevokeAndNeverExposeReferenceInAudit() {
        for (DirectoryBindingKind kind : List.of(DirectoryBindingKind.DIRECTORY_GROUP, DirectoryBindingKind.DIRECTORY_PRINCIPAL)) {
            Fixture f = new Fixture();
            String self = kind == DirectoryBindingKind.DIRECTORY_GROUP ? "opaque-group" : PRINCIPAL;
            f.target = new DirectoryTargetPort.Target(PROFILE, kind, self, NOW.plusSeconds(300), f::publish);
            var service = f.admin(true);
            assertInstanceOf(RoleBindingAdminService.Outcome.SelfGrantRefused.class, f.create(service, f.handle(service), NOW));
            f.directory("self", BACKUP, kind, self, PROFILE);
            assertInstanceOf(RoleBindingAdminService.Outcome.SelfGrantRefused.class, service.revoke(ACTOR, "acting-session", "self", NOW));
            assertTrue(f.audit.isEmpty());
            f.target = new DirectoryTargetPort.Target(PROFILE, kind, "opaque-other", NOW.plusSeconds(300), f::publish);
            var created = assertInstanceOf(RoleBindingAdminService.Outcome.Created.class, f.create(service, f.handle(service), NOW));
            assertFalse(f.audit.toString().contains("opaque-other"));
            assertInstanceOf(RoleBindingAdminService.Outcome.Revoked.class, service.revoke(ACTOR, "acting-session", created.bindingId(), NOW));
        }
    }

    @Test void selectionScopesExpiryReplayAndMutationRacesRefuseWithoutWrites() {
        Fixture f = new Fixture(); var service = f.admin(true);
        String handle = f.handle(service);
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class,
                service.createDirectory("other-actor", "acting-session", BACKUP, handle, PROFILE, f.target.kind(), NOW));
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class, f.create(service, handle, NOW));
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class,
                service.createDirectory(ACTOR, "other-session", BACKUP, f.handle(service), PROFILE, f.target.kind(), NOW));
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class,
                service.createDirectory(ACTOR, "acting-session", BACKUP, f.handle(service), "other-profile", f.target.kind(), NOW));
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class,
                service.createDirectory(ACTOR, "acting-session", BACKUP, f.handle(service), PROFILE, DirectoryBindingKind.DIRECTORY_GROUP, NOW));
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class, f.create(service, f.handle(service), NOW.plusSeconds(120)));
        handle = f.handle(service); f.targetMissing = true;
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class, f.create(service, handle, NOW));
        f.targetMissing = false; handle = f.handle(service);
        f.beforeMutation = () -> f.rows.remove("admin-binding");
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class, f.create(service, handle, NOW));
        assertTrue(f.audit.isEmpty());
    }

    @Test void staleAdminTrustRetirementAndLastAdminProtectionHaveNoRootDirectoryBypass() {
        Fixture f = new Fixture(); var service = f.admin(true);
        String handle = f.handle(service); f.states.clear();
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class, f.create(service, handle, NOW));
        f.state(ACTOR, PRINCIPAL, Set.of(), NOW.plusSeconds(300)); handle = f.handle(service);
        f.currentTrust.set(false);
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class, f.create(service, handle, NOW));
        f.currentTrust.set(true);
        f.directory("other-admin", ADMIN, f.target.kind(), f.target.reference(), PROFILE);
        assertInstanceOf(RoleBindingAdminService.Outcome.LastSecurityAdminRefused.class,
                service.revoke(ACTOR, "acting-session", "other-admin", NOW));
        f.local("enabled-local", true);
        assertInstanceOf(RoleBindingAdminService.Outcome.Revoked.class, service.revoke(ACTOR, "acting-session", "other-admin", NOW));
        assertTrue(f.admin(false).resolveSelections(ACTOR, "acting-session", PROFILE, f.target.kind(), NOW).isEmpty());
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class, f.create(f.admin(false), "unrecognized", NOW));
    }

    @Test void verifiedLoginConflictCarriesOnlyEncryptedProofAndNeverExtendsItsObservation() {
        Fixture f = new Fixture(); f.states.clear(); f.sessionRows.clear();
        LoginFlow flow = new LoginFlow(f.sessions, Duration.ofMinutes(5), Duration.ofHours(10), f.bindings, f.cipher, true);
        var observation = new DirectoryObservation(PROFILE, PRINCIPAL, Set.of("opaque-group"), NOW, NOW.plusSeconds(300), f::publish);
        var success = new AttemptOutcome.Success(ACTOR, observation);
        assertInstanceOf(LoginFlow.LoginResult.NewSession.class, flow.login(success, NOW));
        assertEquals(NOW.plusSeconds(300), f.states.get(ACTOR).validUntil());
        var conflict = assertInstanceOf(LoginFlow.LoginResult.Conflict.class, flow.login(success, NOW.plusSeconds(10)));
        assertInstanceOf(LoginFlow.ResolveResult.TakenOver.class, flow.resolve(conflict.conflictToken(), "takeover", NOW.plusSeconds(20)));
        assertEquals(NOW.plusSeconds(300), f.states.get(ACTOR).validUntil()); // Bound to bind observation, not takeover time.
        conflict = assertInstanceOf(LoginFlow.LoginResult.Conflict.class, flow.login(success, NOW.plusSeconds(30)));
        f.currentTrust.set(false);
        assertInstanceOf(LoginFlow.ResolveResult.Refused.class, flow.resolve(conflict.conflictToken(), "takeover", NOW.plusSeconds(40)));
        f.currentTrust.set(true);
        var other = new AttemptOutcome.Success(ACTOR, new DirectoryObservation(PROFILE, "different-proven-principal", Set.of(), NOW, NOW.plusSeconds(300), f::publish));
        assertInstanceOf(LoginFlow.LoginResult.DirectoryUnavailable.class, flow.login(other, NOW.plusSeconds(50)));
        assertInstanceOf(LoginFlow.LoginResult.DirectoryUnavailable.class, flow.login(success, NOW.plusSeconds(300)));
        assertInstanceOf(LoginFlow.LoginResult.DirectoryUnavailable.class,
                new LoginFlow(f.sessions, Duration.ofMinutes(5), Duration.ofHours(10), f.bindings, f.cipher, false).login(success, NOW));
    }

    @Test void approvedRevalidationRefreshesBothKindsButDisabledFailedAndAmbiguousProofDoNot() {
        Fixture f = new Fixture();
        f.directory("group", BACKUP, DirectoryBindingKind.DIRECTORY_GROUP, "opaque-group", PROFILE);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var enabled = new AtomicBoolean(false);
        var failed = new AtomicBoolean(false);
        LdapRevalidationPort directory = new LdapRevalidationPort() {
            public boolean directoryPostureEnabled() { return enabled.get(); }
            public Result<Set<String>> revalidate(String actor, String principal) { throw new AssertionError("legacy revalidation forbidden"); }
            public Result<DirectoryObservation> revalidatePrincipal(String principal) {
                calls.incrementAndGet();
                if (failed.get()) return Result.err("directory_unavailable", "synthetic failure");
                return Result.ok(new DirectoryObservation(PROFILE, principal, Set.of("opaque-group"),
                        NOW.plusSeconds(100), NOW.plusSeconds(400), f::publish));
            }
        };
        var refresh = new DirectoryAuthorizationRefresh(directory, f.bindings, f.cipher);
        assertFalse(refresh.refresh(ACTOR, NOW.plusSeconds(100))); assertEquals(0, calls.get());
        enabled.set(true);
        assertTrue(refresh.refresh(ACTOR, NOW.plusSeconds(100)));
        assertEquals(NOW.plusSeconds(400), f.states.get(ACTOR).validUntil());
        assertEquals(AuthzOutcome.PERMITTED, f.evaluator().evaluate(ACTOR, Optional.of(RoleToken.BACKUP_ADMIN), NOW.plusSeconds(100)).outcome());
        f.directory("principal", BACKUP, DirectoryBindingKind.DIRECTORY_PRINCIPAL, PRINCIPAL, PROFILE);
        f.rows.remove("group"); assertEquals(AuthzOutcome.PERMITTED,
                f.evaluator().evaluate(ACTOR, Optional.of(RoleToken.BACKUP_ADMIN), NOW.plusSeconds(100)).outcome());
        failed.set(true); assertFalse(refresh.refresh(ACTOR, NOW.plusSeconds(200)));
        assertEquals(NOW.plusSeconds(400), f.states.get(ACTOR).validUntil());
        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED,
                f.evaluator().evaluate(ACTOR, Optional.of(RoleToken.BACKUP_ADMIN), NOW.plusSeconds(400)).outcome());
        failed.set(false); f.currentTrust.set(false);
        assertFalse(refresh.refresh(ACTOR, NOW.plusSeconds(200)));
        assertEquals(NOW.plusSeconds(400), f.states.get(ACTOR).validUntil());
    }

    @Test void existingLocalAdministrationUsesProvenLocalIdsAndRejectsDirectoryRawReferenceAsLegacy() {
        Fixture f = new Fixture(); f.states.clear(); f.sessionRows.clear();
        f.local("acting-local", true); f.local("target-local", true);
        String actor = LocalMechanism.actorFingerprintFor("acting-local");
        f.sessionRows.put("local-session", f.session("local-session", actor));
        var service = f.admin(false);
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class,
                service.createLocal(actor, "local-session", BACKUP, "unproven-directory-reference", NOW));
        var created = assertInstanceOf(RoleBindingAdminService.Outcome.Created.class,
                service.createLocal(actor, "local-session", BACKUP, "target-local", NOW));
        assertEquals(DirectoryBindingKind.LEGACY, f.rows.get(created.bindingId()).bindingKind());
        assertEquals(AuthzOutcome.PERMITTED, f.evaluator().evaluate(LocalMechanism.actorFingerprintFor("target-local"),
                Optional.of(RoleToken.BACKUP_ADMIN), NOW).outcome());
        assertInstanceOf(RoleBindingAdminService.Outcome.NotEvaluated.class,
                service.createDirectory(actor, "local-session", BACKUP, "raw-reference", PROFILE, DirectoryBindingKind.DIRECTORY_PRINCIPAL, NOW));
    }

    @Test void sourceAndIdentityThrottleExpireAndReserveBeforeAnyDirectoryCall() {
        var throttle = new LoginAttemptThrottle();
        for (int i = 0; i < 5; i++) assertTrue(throttle.admit("source", "identity", NOW));
        assertFalse(throttle.admit("other-source", "identity", NOW));
        assertTrue(throttle.admit("source", "identity", NOW.plusSeconds(300)));
        throttle.succeeded("identity");
        for (int i = 0; i < 9; i++) { assertTrue(throttle.admit("source", "another-" + i, NOW.plusSeconds(300))); }
        assertFalse(throttle.admit("source", "new-identity", NOW.plusSeconds(300)));
    }
}
