package com.securityexpert.nexus.ui2.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real repository decisions; PostgreSQL locking still requires integration validation. */
class JooqFailoverApprovalTest {
    private static final Set<String> MEMBERS=Set.of("member-a","member-b");
    private static final Instant NOW=Instant.parse("2026-10-06T09:00:00Z");
    static final class Database implements MockDataProvider, TransactionBoundary {
        final Map<String,Object> approval=new LinkedHashMap<>();
        final Map<String,Object> run=new LinkedHashMap<>();
        final DSLContext sql=DSL.using(new MockConnection(this),SQLDialect.POSTGRES);
        final JooqCpFailoverRepository repository=new JooqCpFailoverRepository(this,true, new com.securityexpert.nexus.ui2.platform.JobWindowPolicy(
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), 60));
        Set<String> members=MEMBERS;
        String consumed,incident,dispatchRef;
        boolean unresolvedDispatch,fleetMutation;
        boolean owner=true,expiredAtBoundary;
        int insertedRuns,insertedJobs,writes;
        Instant clock=NOW;
        Database() {
            approval.put("approval_id","request-a"); approval.put("request_revision",1L);
            approval.put("cluster_ref","unit-a"); approval.put("vs_id",null);
            approval.put("vendor","check_point"); approval.put("initiated_by","principal-a");
            approval.put("execution_nonce","nonce-a"); approval.put("policy",JooqCpFailoverRepository.ADMIN_SINGLE);
            approval.put("policy_version",1); approval.put("approved_by","principal-a");
            approval.put("revoked_at",null); approval.put("warning_confirmed_at",NOW.atOffset(ZoneOffset.UTC));
            approval.put("member_set_revision",JooqCpFailoverRepository.memberSetRevision(MEMBERS));
            approval.put("window_from",NOW.minusSeconds(30).atOffset(ZoneOffset.UTC));
            approval.put("window_until",NOW.plusSeconds(30).atOffset(ZoneOffset.UTC));
            run.put("run_id","run-a"); run.put("run_kind","FAILOVER"); run.put("cluster_ref","unit-a");
            run.put("vs_id",null); run.put("vendor","check_point"); run.put("state","PRECHECK");
            run.put("request_revision",1L); run.put("approval_id","request-a"); run.put("requested_by","principal-a"); run.put("job_id","job-a");
        }
        @Override public <T> T inTransaction(Function<DSLContext,T> work) { return work.apply(sql); }
        private MockResult[] row(Map<String,Object> values) {
            Field<?>[] fields=values.entrySet().stream().map(e -> DSL.field(e.getKey(),
                e.getValue()==null?String.class:e.getValue().getClass())).toArray(Field[]::new);
            Result<org.jooq.Record> rows=DSL.using(SQLDialect.POSTGRES).newResult(fields);
            var record=DSL.using(SQLDialect.POSTGRES).newRecord(fields);
            record.fromArray(values.values().toArray()); rows.add(record);
            return new MockResult[]{new MockResult(1,rows)};
        }
        private MockResult[] none() { return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("empty",String.class)))}; }
        @Override public MockResult[] execute(MockExecuteContext ctx) {
            String query=ctx.sql();
            if (query.startsWith("SET LOCAL") || query.startsWith("select pg_advisory")) return new MockResult[]{new MockResult(0)};
            if (query.startsWith("select * from failover_approval")) return row(approval);
            if (query.startsWith("select * from failover_run")) return row(run);
            if (query.startsWith("select clock_timestamp")) return row(Map.of("checked_at",clock.atOffset(ZoneOffset.UTC)));
            if (query.startsWith("select window_from")) return row(Map.of("started",true));
            if (query.startsWith("select job_id from jobs")) return owner?row(Map.of("job_id","job-a")):none();
            if (query.startsWith("select run_id from failover_run"))
                return query.contains("approval_id") && consumed!=null?row(Map.of("run_id",consumed)):none();
            if (query.startsWith("select resolved.device_id")) {
                var dsl=DSL.using(SQLDialect.POSTGRES);
                var id=DSL.field("device_id",String.class); var vendor=DSL.field("vendor_hint",String.class);
                var role=DSL.field("role",String.class); var disabled=DSL.field("disabled",Boolean.class);
                var state=DSL.field("enrollment_state",String.class); var rows=dsl.newResult(id,vendor,role,disabled,state);
                members.stream().sorted().forEach(m -> rows.add(dsl.newRecord(id,vendor,role,disabled,state)
                    .values(m,"check_point","gateway",false,"ENROLLED")));
                return new MockResult[]{new MockResult(rows.size(),rows)};
            }
            if (query.startsWith("select target_member_ids")) return row(Map.of("unchanged",MEMBERS.equals(members)));
            if (query.startsWith("select i.nonce as dispatch_ref from failover_quarantine")) {
                assertTrue(query.contains("i.run_id=q.execution_id and i.observation<>'CONFIRMED'"));
                assertTrue(query.contains("where q.active and (q.cluster_ref=?"));
                assertTrue(query.contains("q.quarantined_member_ids @> jsonb_build_array"));
                return incident==null?none():row(Collections.singletonMap("dispatch_ref",dispatchRef));
            }
            if (query.startsWith("select 1 from failover_dispatch_intent"))
                return unresolvedDispatch?row(Map.of("unresolved",1)):none();
            if (query.startsWith("select 1 from failover_run")) return fleetMutation?row(Map.of("active",1)):none();
            if (query.startsWith("insert into failover_run")) { insertedRuns++; consumed=(String)ctx.bindings()[0]; }
            else if (query.startsWith("insert into jobs")) insertedJobs++;
            else if (query.startsWith("update failover_approval set approved_by")) approval.put("approved_by",ctx.bindings()[0]);
            else if (query.startsWith("update failover_approval set warning_confirmed_at")) { /* persisted confirmation */ }
            else if (query.startsWith("update failover_run set job_id")) { /* queued job */ }
            else if (query.startsWith("update failover_run set mutation_possible")) {
                assertTrue(query.contains("window_until>clock_timestamp()"));
                assertTrue(query.contains("ui2_job_owner_valid"));
                if (expiredAtBoundary) return new MockResult[]{new MockResult(0)};
                writes++;
            } else throw new AssertionError("Unexpected SQL: "+query);
            return new MockResult[]{new MockResult(1)};
        }
        JooqCpFailoverRepository.Decision start(String nonce) {
            return repository.requestBound("unit-a",null,NOW,"principal-a","member-a",true,"check_point",MEMBERS,
                "request-a",1,nonce,true,(String)approval.get("policy"));
        }
        String dispatch() {
            return repository.mutationAdmission("run-a","unit-a",null,"check_point",MEMBERS,true,"job-a",1);
        }
    }

    @Test void adminSinglePathConsumesOnceAndRetryReturnsSameRun() {
        Database db=new Database();
        var first=db.start("nonce-a");
        assertEquals("ADMITTED",first.code());
        assertEquals(first,db.start("nonce-a"));
        db.approval.put("revoked_at",NOW.atOffset(ZoneOffset.UTC));
        assertEquals(first,db.start("nonce-a"),"A retry identifies the existing run; it never grants another dispatch");
        assertEquals(1,db.insertedRuns); assertEquals(1,db.insertedJobs);
        assertEquals("NONCE_MISMATCH",db.start("nonce-other").code());
    }
    @Test void operationAdminRequiresSecondDistinctPrincipalNotAnotherSession() {
        Database db=new Database(); db.approval.put("policy",JooqCpFailoverRepository.TWO_PERSON);
        db.approval.put("approved_by",null);
        assertEquals("SECOND_APPROVAL_REQUIRED",db.start("nonce-a").code());
        for (int session=0;session<2;session++) assertEquals("SELF_APPROVAL",
            db.repository.approveRequest("request-a",1,"principal-a","check_point",MEMBERS));
        assertEquals("APPROVED",db.repository.approveRequest("request-a",1,"principal-b","check_point",MEMBERS));
        assertEquals("ADMITTED",db.start("nonce-a").code());
        assertEquals(1,db.insertedRuns);
    }
    @Test void targetRevisionPolicyAndMembershipChangesInvalidateApproval() {
        for (String field:List.of("cluster_ref","vs_id","request_revision","member_set_revision","policy_version")) {
            Database db=new Database();
            db.approval.put(field,field.equals("request_revision")?2L:field.equals("policy_version")?2:"changed");
            assertNotEquals("ADMITTED",db.start("nonce-a").code()); assertEquals(0,db.insertedRuns);
        }
        Database db=new Database(); db.members=Set.of("member-a","member-new");
        assertEquals("MEMBER_SET_CHANGED",db.start("nonce-a").code()); assertEquals(0,db.insertedRuns);
    }
    @Test void warningAndNonceCannotBeReusedForAnotherRequestOrInitiator() {
        Database db=new Database(); db.approval.put("execution_nonce","nonce-b");
        assertEquals("NONCE_MISMATCH",db.start("nonce-a").code());
        db.approval.put("execution_nonce","nonce-a"); db.approval.put("initiated_by","principal-b");
        assertEquals("WRONG_INITIATOR",db.start("nonce-a").code()); assertEquals(0,db.insertedJobs);
    }
    @Test void eachDispatchChecksWindowRevocationOwnerIncidentAndFinalExpiry() {
        for (String phase:List.of("FAILING_OVER","RETURNING")) {
            Database db=new Database(); db.run.put("state",phase);
            assertEquals("ADMITTED",db.dispatch());
            db.clock=NOW.plusSeconds(30); assertEquals("WINDOW_EXPIRED",db.dispatch());
            db.clock=NOW; db.approval.put("revoked_at",NOW.atOffset(ZoneOffset.UTC));
            assertEquals("APPROVAL_REVOKED",db.dispatch()); db.approval.put("revoked_at",null);
            db.owner=false; assertEquals("OWNERSHIP_LOST",db.dispatch()); db.owner=true;
            db.incident="incident-a"; assertEquals("OPEN_INCIDENT",db.dispatch()); db.incident=null;
            db.expiredAtBoundary=true; assertEquals("DISPATCH_AUTHORITY_EXPIRED",db.dispatch());
            assertEquals(1,db.writes);
        }
    }
    @Test void opaqueMemberRevisionIsOrderIndependentAndFramed() {
        assertEquals(JooqCpFailoverRepository.memberSetRevision(MEMBERS),
            JooqCpFailoverRepository.memberSetRevision(new LinkedHashSet<>(List.of("member-b","member-a"))));
        assertNotEquals(JooqCpFailoverRepository.memberSetRevision(Set.of("01","2")),
            JooqCpFailoverRepository.memberSetRevision(Set.of("1","02")));
    }
}
