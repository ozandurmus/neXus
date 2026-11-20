package com.securityexpert.nexus.ui2.integration.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.platform.LocalPrincipalFingerprint;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * V257 (backlog failover_approver_role_recheck_at_worker), real PostgreSQL 16: the authority that admitted the initiator
 * and the approver is recorded with the request, and the durable admission rechecks it before any device contact --
 * initiator disabled, approver demoted, one person on both sides -- but never for the run's return write. Synthetic
 * identities and bindings only; no executor, no device.
 */
class FailoverActorAuthorityRecheckTest {
    private static final String CLUSTER = "CLS-TEST-AUTH";

    private static <T> T mutate(Ui2PostgresFixture fixture, Function<DSLContext, T> work) {
        return new AuditedTransactionBoundary(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)))
            .inTransaction(Ui2Rows.ACTOR, "synthetic_actor_authority_test", work);
    }
    private static JooqCpFailoverRepository repository(Ui2PostgresFixture fixture) {
        return new JooqCpFailoverRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)),
            true, JobWindowTestPolicy.PERMISSIVE);
    }
    /** A synthetic local identity; returns its actor fingerprint. */
    private static String identity(Ui2PostgresFixture fixture, String id) {
        mutate(fixture, dsl -> dsl.execute("insert into local_credentials(local_identity_id,local_identity_name,verifier,salt,"
            + "algorithm_id,memory_cost_kib,time_cost,parallelism,enabled,created_by_actor_fingerprint,password_set_at) "
            + "values({0},{1},decode('00','hex'),decode('00','hex'),'argon2id',1,1,1,true,{2},now())", id, "synthetic-" + id,
            Ui2Rows.ACTOR));
        return LocalPrincipalFingerprint.forLocalIdentity(id);
    }
    private static String binding(Ui2PostgresFixture fixture, String role) {
        String id = UUID.randomUUID().toString();
        mutate(fixture, dsl -> dsl.execute("insert into role_bindings(binding_id,role_token,group_reference_encrypted,"
            + "group_reference_key_id,created_by_actor_fingerprint) values({0},{1},decode('00','hex'),'synthetic-key',{2})",
            id, role, Ui2Rows.ACTOR));
        return id;
    }
    private static void revoke(Ui2PostgresFixture fixture, String binding) {
        mutate(fixture, dsl -> dsl.execute("update role_bindings set revoked_at=now(),revoked_by_actor_fingerprint={1} "
            + "where binding_id={0}", binding, Ui2Rows.ACTOR));
    }
    private static void enabled(Ui2PostgresFixture fixture, String id, boolean on) {
        mutate(fixture, dsl -> dsl.execute("update local_credentials set enabled={1} where local_identity_id={0}", id, on));
    }
    private static String standing(Ui2PostgresFixture fixture, String actor, String authority, String binding) {
        return mutate(fixture, dsl -> dsl.fetchOne("select ui2_failover_actor_standing({0},{1},{2})", actor, authority, binding)
            .get(0, String.class));
    }
    private static FailoverActorAuthority.Grant local(String binding) {
        return new FailoverActorAuthority.Grant(FailoverActorAuthority.LOCAL, binding, RoleToken.OPERATION_ADMIN);
    }
    private static Set<String> pair(Ui2PostgresFixture fixture) throws Exception {
        try (var connection = fixture.appConnection()) {
            String credential = Ui2Rows.insertCredentialReference(connection);
            String a = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            String b = Ui2Rows.insertDevice(connection, credential, "ENROLLED");
            Ui2Rows.insertEndpoint(connection, a); Ui2Rows.insertEndpoint(connection, b);
            mutate(fixture, dsl -> dsl.execute("update devices set vendor_hint='check_point',role='gateway',cluster_member_ref={0} "
                + "where device_id in ({1},{2})", CLUSTER, a, b));
            return Set.of(a, b);
        }
    }
    private static String unitRef() {
        return UUID.nameUUIDFromBytes(CLUSTER.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record Execution(String run, String job, long epoch) {}

    /** End a refused run and its job the way the worker does after a pre-contact refusal. */
    private static void end(Ui2PostgresFixture fixture, JooqCpFailoverRepository repo, Execution e, String code) {
        repo.state(e.run(), "STOPPED", "STOPPED", code, null, code);
        var leases = new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(
            new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)), JobWindowTestPolicy.PERMISSIVE);
        assertTrue(leases.transitionState(e.job(), e.epoch(), "EXECUTING", "FAILED", Ui2Rows.ACTOR, "synthetic_end", code));
    }

    /** A two-person request by {@code initiator}, approved by {@code approver}, started at once and claimed by a worker. */
    private static Execution twoPersonRun(Ui2PostgresFixture fixture, JooqCpFailoverRepository repo, Set<String> members,
            String initiator, String initiatorBinding, String approver, String approverBinding) {
        var request = repo.createRequestApproval(UUID.randomUUID().toString(), CLUSTER, null, unitRef(), members,
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Synthetic two-person request", initiator,
            "check_point", JooqCpFailoverRepository.TWO_PERSON, JooqCpFailoverRepository.OPERATION_FAILOVER, local(initiatorBinding));
        assertEquals("APPROVED", repo.approveRequest(request.approval().id(), request.revision(), approver, "check_point",
            members, local(approverBinding)));
        var decision = repo.requestBound(CLUSTER, null, Instant.now(), initiator, members.stream().sorted().findFirst().orElseThrow(),
            true, "check_point", members, request.approval().id(), request.revision(), request.executionNonce(), true,
            JooqCpFailoverRepository.TWO_PERSON);
        assertEquals("ADMITTED", decision.code());
        mutate(fixture, dsl -> dsl.execute("update module_runtime_control set owner_instance='general-synthetic',"
            + "owner_heartbeat_at=now() where module='general'"));
        var leases = new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(
            new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)), JobWindowTestPolicy.PERMISSIVE);
        var job = leases.claimNext("general-synthetic", List.of("cp_cluster_failover"), Duration.ofMinutes(10)).orElseThrow();
        assertTrue(leases.transitionState(job.jobId(), job.leaseEpoch(), "CLAIMED", "EXECUTING", Ui2Rows.ACTOR, "synthetic_start"));
        return new Execution(decision.runId(), job.jobId(), job.leaseEpoch());
    }

    @Test
    void initiatorDisabledOrApproverDemotedIsRefusedBeforeContactButNeverAtTheReturnWrite() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_actor_authority")) {
            var repo = repository(fixture);
            var members = pair(fixture);
            String initiator = identity(fixture, "initiator-a"), approver = identity(fixture, "approver-a");
            String initiatorBinding = binding(fixture, RoleToken.OPERATION_ADMIN), approverBinding = binding(fixture, RoleToken.OPERATION_ADMIN);
            var e = twoPersonRun(fixture, repo, members, initiator, initiatorBinding, approver, approverBinding);
            // Both recorded with the request; the record is insert-only.
            long recorded = mutate(fixture, dsl -> dsl.fetchOne("select count(*) from failover_actor_authority").get(0, Long.class));
            assertEquals(2L, recorded);
            assertThrows(RuntimeException.class, () -> mutate(fixture, dsl ->
                dsl.execute("update failover_actor_authority set binding_id=null")));

            // Role kept: admitted before contact.
            assertEquals("ADMITTED", repo.mutationAdmission(e.run(), CLUSTER, null, "check_point", members, false, e.job(), e.epoch()));
            // Initiator disabled since the request: refused before contact and before the first write.
            enabled(fixture, "initiator-a", false);
            assertEquals(FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED,
                repo.mutationAdmission(e.run(), CLUSTER, null, "check_point", members, false, e.job(), e.epoch()));
            assertEquals(FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED,
                repo.mutationAdmission(e.run(), CLUSTER, null, "check_point", members, true, e.job(), e.epoch()));
            enabled(fixture, "initiator-a", true);
            end(fixture, repo, e, FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED);

            // A new run whose approver is demoted before the start: refused before contact.
            String approverB = identity(fixture, "approver-b"), approverBBinding = binding(fixture, RoleToken.OPERATION_ADMIN);
            var second = twoPersonRun(fixture, repo, members, initiator, initiatorBinding, approverB, approverBBinding);
            revoke(fixture, approverBBinding);
            assertEquals(FailoverActorAuthority.APPROVER_NOT_AUTHORIZED,
                repo.mutationAdmission(second.run(), CLUSTER, null, "check_point", members, false, second.job(), second.epoch()));
            end(fixture, repo, second, FailoverActorAuthority.APPROVER_NOT_AUTHORIZED);

            // The same person on both sides through the identity's probe actor: refused as one person.
            String probe = LocalPrincipalFingerprint.forLocalIdentityProbe("initiator-a");
            var third = twoPersonRun(fixture, repo, members, initiator, initiatorBinding, probe, initiatorBinding);
            assertEquals(FailoverActorAuthority.APPROVER_IS_INITIATOR,
                repo.mutationAdmission(third.run(), CLUSTER, null, "check_point", members, false, third.job(), third.epoch()));
            end(fixture, repo, third, FailoverActorAuthority.APPROVER_IS_INITIATOR);

            // Once the first write was admitted, a demotion never strands the member: the return write is not refused.
            var fourth = twoPersonRun(fixture, repo, members, initiator, initiatorBinding, approver, approverBinding);
            assertEquals("ADMITTED", repo.mutationAdmission(fourth.run(), CLUSTER, null, "check_point", members, true,
                fourth.job(), fourth.epoch()));
            revoke(fixture, approverBinding);
            assertEquals("ADMITTED", repo.mutationAdmission(fourth.run(), CLUSTER, null, "check_point", members, true,
                fourth.job(), fourth.epoch()));
        }
    }

    @Test
    void theStandingFunctionAnswersOneClosedWordPerActor() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_actor_standing")) {
            String admin = identity(fixture, "admin-a"), root = identity(fixture, "root-a");
            String held = binding(fixture, RoleToken.SECURITY_ADMIN), viewer = binding(fixture, RoleToken.REPLAY_VIEWER);
            assertEquals("HELD", standing(fixture, admin, "ui2_local", held));
            assertEquals("ROLE_NOT_HELD", standing(fixture, admin, "ui2_local", viewer), "a non-administrator role never counts");
            assertEquals("ROLE_NOT_HELD", standing(fixture, admin, "ui2_local", "binding-unknown"));
            assertEquals("IDENTITY_UNKNOWN", standing(fixture, "synthetic-gone", "ui2_local", held));
            // A directory actor: the binding is what can be rechecked here.
            assertEquals("HELD", standing(fixture, "synthetic-directory-actor", "ui2_ldap", held));
            assertEquals("NOT_RECORDED", standing(fixture, "synthetic-legacy-actor", null, null));
            // The root authority holds only while that identity is the root.
            assertEquals("ROLE_NOT_HELD", standing(fixture, root, "ui2_root", null));
            mutate(fixture, dsl -> dsl.execute("insert into root_identity(local_identity_id) values('root-a')"));
            assertEquals("HELD", standing(fixture, root, "ui2_root", null));
            enabled(fixture, "admin-a", false);
            assertEquals("IDENTITY_DISABLED", standing(fixture, admin, "ui2_local", held));
            assertEquals("IDENTITY_DISABLED", standing(fixture, admin, null, null), "a pre-V257 record still checks the identity");
            revoke(fixture, held);
            assertEquals("ROLE_NOT_HELD", standing(fixture, "synthetic-directory-actor", "ui2_ldap", held));
            Boolean same = mutate(fixture, dsl -> dsl.fetchOne("select ui2_failover_same_person({0},{1})", admin,
                LocalPrincipalFingerprint.forLocalIdentityProbe("admin-a")).get(0, Boolean.class));
            Boolean other = mutate(fixture, dsl -> dsl.fetchOne("select ui2_failover_same_person({0},{1})", admin, root)
                .get(0, Boolean.class));
            assertTrue(same); assertFalse(other);
        }
    }

    @Test
    void aSequenceStopsAtTheNextVirtualSystemWhenItsInitiatorWasDisabled() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_actor_sequence")) {
            var repo = repository(fixture);
            var members = pair(fixture);
            String admin = identity(fixture, "sequence-admin");
            String held = binding(fixture, RoleToken.SECURITY_ADMIN);
            var grant = new FailoverActorAuthority.Grant(FailoverActorAuthority.LOCAL, held, RoleToken.SECURITY_ADMIN);
            var approval = repo.createRequestApproval(UUID.randomUUID().toString(), CLUSTER, null, unitRef(), members,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Synthetic sequence", admin, "check_point",
                JooqCpFailoverRepository.ADMIN_SINGLE, JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS, grant);
            var steps = List.of(new JooqCpFailoverRepository.SequenceStep("12", UUID.randomUUID().toString()),
                new JooqCpFailoverRepository.SequenceStep("13", UUID.randomUUID().toString()));
            String target = members.stream().sorted().findFirst().orElseThrow();
            var first = repo.requestSequence(CLUSTER, admin, target, members, approval.approval().id(), approval.revision(),
                approval.executionNonce(), true, approval.policy(), steps, null, false);
            assertEquals("ADMITTED", first.code());
            String sequence = mutate(fixture, dsl -> dsl.fetchOne("select sequence_id from failover_run where run_id={0}",
                first.runId()).get(0, String.class));
            repo.state(first.runId(), "DONE", "DONE", "SUCCEEDED", null, "NO_PROBLEMS_FOUND");
            enabled(fixture, "sequence-admin", false);
            assertEquals(FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED, repo.advanceSequence(sequence, steps, target));
            var ended = repo.latestSequence(CLUSTER).orElseThrow();
            assertEquals("STOPPED", ended.state());
            assertEquals(FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED, ended.outcome());
            assertEquals("13", ended.failedVsId());
        }
    }

    @Test
    void directoryAuthorityStaleRemovedUnreachableAndRefreshedMemberFlowAgainstPostgres() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_actor_ldap_refresh")) {
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var repo = new JooqCpFailoverRepository(boundary, true, JobWindowTestPolicy.PERMISSIVE);
            var members = pair(fixture);
            String target = members.stream().sorted().findFirst().orElseThrow();

            // Set up real DB-backed identity repositories + fake LDAP directory port.
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            var cipher = com.securityexpert.nexus.ui2.platform.GroupReferenceCipher.fromBase64Key(
                java.util.Base64.getEncoder().encodeToString(key), "synthetic-key");
            String profile = "synthetic-ad-profile";
            String groupDn = "CN=FailoverAdmins,DC=example,DC=invalid";
            var bindings = new com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository(boundary);
            var actors = new com.securityexpert.nexus.ui2.persistence.identity.JooqActorAuthzStateRepository(boundary);
            var sessions = new com.securityexpert.nexus.ui2.persistence.identity.JooqSessionRepository(boundary);
            String bindingId = UUID.randomUUID().toString();
            bindings.createDirectory(new com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord(
                bindingId, RoleToken.OPERATION_ADMIN,
                cipher.encryptDirectory(groupDn, profile, com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_GROUP),
                cipher.keyId(), Ui2Rows.ACTOR, Instant.now(), java.util.Optional.empty(), java.util.Optional.empty(),
                com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_GROUP, profile),
                "synthetic_ldap_binding");

            String initiator = "dir-init-001", approver = "dir-appr-002";
            String initPrincipal = "CN=init,OU=Ops,DC=example,DC=invalid";
            String apprPrincipal = "CN=appr,OU=Ops,DC=example,DC=invalid";
            var dirMembers = new java.util.HashMap<String, Set<String>>();
            var reachable = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.function.BiConsumer<String, String> seedProof = (actor, principal) -> {
                Instant now = Instant.now();
                actors.upsertDirectory(new com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord(
                    actor, Set.of(groupDn), now.minusSeconds(30), now.plusSeconds(270), profile,
                    cipher.encryptDirectory(principal, profile, com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_PRINCIPAL),
                    cipher.keyId()));
                dirMembers.put(principal, Set.of(groupDn));
            };
            seedProof.accept(initiator, initPrincipal);
            seedProof.accept(approver, apprPrincipal);

            var port = new com.securityexpert.nexus.ui2.platform.LdapRevalidationPort() {
                @Override public boolean directoryPostureEnabled() { return true; }
                @Override public com.securityexpert.nexus.ui2.platform.Result<Set<String>> revalidate(String a, String b) {
                    return com.securityexpert.nexus.ui2.platform.Result.err("directory_identity_not_proven", "typed");
                }
                @Override public com.securityexpert.nexus.ui2.platform.Result<com.securityexpert.nexus.ui2.platform.DirectoryObservation> revalidatePrincipal(String principal) {
                    if (!reachable.get()) return com.securityexpert.nexus.ui2.platform.Result.err("directory_unavailable", "connect_failed");
                    var groups = dirMembers.getOrDefault(principal, Set.of());
                    if (groups.isEmpty()) return com.securityexpert.nexus.ui2.platform.Result.err("access_group_lost", "not_in_group");
                    return com.securityexpert.nexus.ui2.platform.Result.ok(
                        new com.securityexpert.nexus.ui2.platform.DirectoryObservation(profile, principal, groups, r -> { r.run(); return true; }));
                }
            };
            var refresh = new com.securityexpert.nexus.ui2.service.security.DirectoryAuthorizationRefresh(
                port, bindings, cipher, actors, sessions);
            var rbac = new com.securityexpert.nexus.ui2.service.security.RbacEvaluator(bindings, actors, cipher);
            rbac.useDirectoryRefresh(refresh);

            // Real JooqDeviceRepository + synthetic proxies for inventory and SSH trust so CpFailoverService uses PostgreSQL.
            var devices = new com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository(boundary);
            List<String> sortedMembers = members.stream().sorted().toList();
            var inventory = (com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository)
                java.lang.reflect.Proxy.newProxyInstance(
                    com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository.class.getClassLoader(),
                    new Class<?>[] { com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasRun" -> true;
                        case "findLatestContextIds" -> List.of("physical");
                        case "findLatestHaFacts" -> List.of(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact(
                            "ha-" + args[0], "physical", sortedMembers.get(0).equals(args[0]) ? "ACTIVE" : "STANDBY",
                            java.util.Optional.empty(),
                            com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact.SOURCE_CP_CPHAPROB_STAT));
                        case "findLatestRun" -> java.util.Optional.of(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun(
                            "inv-" + args[0], (String) args[0], "job-" + args[0], Instant.now(), 1,
                            List.of(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext("physical", List.of(), List.of())),
                            List.of(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact(
                                "ha-" + args[0], "physical", sortedMembers.get(0).equals(args[0]) ? "ACTIVE" : "STANDBY",
                                java.util.Optional.empty(),
                                com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact.SOURCE_CP_CPHAPROB_STAT))));
                        default -> List.of();
                    });
            var trust = (com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository)
                java.lang.reflect.Proxy.newProxyInstance(
                    com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository.class.getClassLoader(),
                    new Class<?>[] { com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository.class },
                    (proxy, method, args) -> List.of("ssh-ed25519"));
            var service = new com.securityexpert.nexus.ui2.service.failover.CpFailoverService(
                devices, inventory, repo, rbac, trust, new com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch(true),
                JobWindowTestPolicy.PERMISSIVE);

            // Create and approve a 2-person failover request while both are in the AD group.
            String reqId = UUID.randomUUID().toString();
            var created = service.createApprovalRequest(reqId, unitRef(), unitRef(),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Directory two-person drill",
                initiator, "check_point");
            var approved = service.secondApproval(reqId, created.revision(), unitRef(), unitRef(), approver, "check_point");

            // 1. Directory down when starting -> refused with DIRECTORY_UNREACHABLE_AT_START (approval remains reusable).
            reachable.set(false);
            assertEquals(FailoverActorAuthority.DIRECTORY_UNREACHABLE,
                assertThrows(com.securityexpert.nexus.ui2.service.failover.CpFailoverService.Refusal.class,
                    () -> service.request(unitRef(), unitRef(), null, initiator, "check_point",
                        reqId, approved.revision(), approved.executionNonce(), true)).code());

            // 2. Directory back up, approver removed from the AD group -> refused with APPROVER_NOT_AUTHORIZED_AT_START.
            reachable.set(true);
            dirMembers.put(apprPrincipal, Set.of());
            assertEquals(FailoverActorAuthority.APPROVER_NOT_AUTHORIZED,
                assertThrows(com.securityexpert.nexus.ui2.service.failover.CpFailoverService.Refusal.class,
                    () -> service.request(unitRef(), unitRef(), null, initiator, "check_point",
                        reqId, approved.revision(), approved.executionNonce(), true)).code());

            // 3. Initiator removed from the AD group -> refused with INITIATOR_NOT_AUTHORIZED_AT_START.
            seedProof.accept(approver, apprPrincipal);
            dirMembers.put(initPrincipal, Set.of());
            assertEquals(FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED,
                assertThrows(com.securityexpert.nexus.ui2.service.failover.CpFailoverService.Refusal.class,
                    () -> service.request(unitRef(), unitRef(), null, initiator, "check_point",
                        reqId, approved.revision(), approved.executionNonce(), true)).code());

            // 4. Both in the AD group -> service refreshes both, records fresh authority rows, worker admits the run;
            //    if the recorded directory authority row is older than 5 minutes, worker refuses with DIRECTORY_AUTHORITY_STALE_AT_START.
            seedProof.accept(initiator, initPrincipal);
            seedProof.accept(approver, apprPrincipal);
            String runId = service.request(unitRef(), unitRef(), null, initiator, "check_point",
                reqId, approved.revision(), approved.executionNonce(), true);
            mutate(fixture, dsl -> dsl.execute("update module_runtime_control set owner_instance='general-synthetic',"
                + "owner_heartbeat_at=now() where module='general'"));
            var leases = new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(
                boundary, JobWindowTestPolicy.PERMISSIVE);
            var job = leases.claimNext("general-synthetic", List.of("cp_cluster_failover"), Duration.ofMinutes(10)).orElseThrow();
            assertTrue(leases.transitionState(job.jobId(), job.leaseEpoch(), "CLAIMED", "EXECUTING", Ui2Rows.ACTOR, "synthetic_start"));
            assertEquals("ADMITTED", repo.mutationAdmission(runId, CLUSTER, null, "check_point", members, false,
                job.jobId(), job.leaseEpoch()));

            // Age the recorded directory authority past 5 minutes inside a rolled-back savepoint: worker refuses as stale.
            try (var admin = fixture.adminDatabaseConnection(); var st = admin.createStatement()) {
                admin.setAutoCommit(false);
                st.execute("SET session_replication_role = replica");
                st.execute("UPDATE failover_actor_authority SET recorded_at = clock_timestamp() - interval '6 minutes' "
                    + "WHERE record_id = '" + reqId + "'");
                st.execute("SET session_replication_role = origin");
                admin.commit();
            }
            assertEquals(FailoverActorAuthority.DIRECTORY_AUTHORITY_STALE,
                repo.mutationAdmission(runId, CLUSTER, null, "check_point", members, false, job.jobId(), job.leaseEpoch()));
            // Refreshing via the service appends a fresh row and the worker admits again, including the first write.
            service.startDue(); // no due runs, no-op
            repo.recordActorAuthority(FailoverActorAuthority.KIND_APPROVAL, reqId, FailoverActorAuthority.INITIATOR,
                initiator, new FailoverActorAuthority.Grant(FailoverActorAuthority.LDAP, bindingId, RoleToken.OPERATION_ADMIN));
            repo.recordActorAuthority(FailoverActorAuthority.KIND_APPROVAL, reqId, FailoverActorAuthority.APPROVER,
                approver, new FailoverActorAuthority.Grant(FailoverActorAuthority.LDAP, bindingId, RoleToken.OPERATION_ADMIN));
            assertEquals("ADMITTED", repo.mutationAdmission(runId, CLUSTER, null, "check_point", members, true,
                job.jobId(), job.leaseEpoch()));
        }
    }

    /** Memory "migration dry-run before deploy": V257 re-runs twice inside one rolled-back transaction. */
    @Test
    void v257IsRepeatableInsideARolledBackTransaction() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("failover_actor_dry_run");
                var migrate = fixture.migrateConnection(); var statement = migrate.createStatement()) {
            migrate.setAutoCommit(false);
            String sql = java.nio.file.Files.readString(java.nio.file.Path.of("../service/src/main/resources/db/migration",
                "V257__failover_actor_authority_recheck.sql"));
            statement.execute(sql);
            statement.execute(sql);
            try (var rows = statement.executeQuery("select has_function_privilege('ui2_app',"
                    + "'public.ui2_failover_actor_standing(text,text,text)','EXECUTE'), has_table_privilege('ui2_app',"
                    + "'public.failover_actor_authority','UPDATE')")) {
                rows.next();
                assertTrue(rows.getBoolean(1));
                assertFalse(rows.getBoolean(2), "the authority record is insert-only for the application");
            }
            migrate.rollback();
        }
    }
}
