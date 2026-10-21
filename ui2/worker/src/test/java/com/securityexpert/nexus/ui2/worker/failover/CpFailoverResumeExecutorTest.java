package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;

/**
 * Resume the return step (docs/design/CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md §7 groups 4–9) against a simulated
 * two-member transport. No socket is opened. The stopped run's former active is FW-TEST-01 (down by admin), its
 * former standby FW-TEST-02 carries the traffic.
 */
class CpFailoverResumeExecutorTest {
    private static final String CLUSTER="CLS-TEST-01", A="FW-TEST-01", B="FW-TEST-02";
    private static final String UP_LITERAL="bash -lc 'clusterXL_admin up'";
    private static final String FW_STAT="HOST POLICY DATE\nlocalhost Sample_Policy 10Sep2018 14:01:25 : [>eth0]\n";
    private static final String CPSTAT="Policy name: Sample_Policy\nInstall time: 2026-10-06 12:00:00\n";

    /** Only the resume API: any run-row write (state, workerState, prepareDispatch, dispatch, command) fails the test. */
    private static final class Store extends JooqCpFailoverRepository {
        final List<String> checks=new ArrayList<>(), ledger=new ArrayList<>(), transitions=new ArrayList<>();
        List<String> admissions=new ArrayList<>(List.of("ADMITTED","ADMITTED"));
        int admissionCalls, prepared, replies, observations;
        String state="QUEUED", outcome, message;
        boolean notSent, baseline=true;
        /** A §2.1 refusal found by the send transaction itself (Argon review 2026-10-21 P2-2): nothing is sent. */
        String refusedInSend;
        Runnable beforeSend=() -> {};
        final List<Boolean> admissionAtStart=new ArrayList<>();
        Store() { super(new TransactionBoundary() {
            public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext,T> ignored) {
                throw new AssertionError("Database access in fake test");
            }
        }, true); }
        Resume resume() {
            return new Resume("resume-1","run-1",CLUSTER,A,B,List.of(A,B),"revision-1","run-1",ADMIN_SINGLE,"actor-1",
                Instant.now(),Instant.now().plusSeconds(1800),false,false,"actor-1","job-r",state,state,null,null,null,
                prepared>0,null);
        }
        @Override public Optional<Run> runByJob(String id) { return Optional.empty(); }
        @Override public Optional<Resume> resumeByJob(String id) { return Optional.of(resume()); }
        @Override public String resumeAdmission(String resumeId,String jobId,long epoch,boolean atStart) {
            admissionAtStart.add(atStart);
            return admissions.get(Math.min(admissionCalls++,admissions.size()-1));
        }
        @Override public boolean resumeState(String id,long epoch,String next,String step,String result,String failed,String text) {
            if (java.util.Set.of("DONE","STOPPED").contains(state)) return false;
            state=next; outcome=result; message=text; transitions.add(next);
            return true;
        }
        @Override public List<Check> preBaseline(String runId) {
            if (!baseline) return List.of();
            List<Check> rows=new ArrayList<>();
            String fw=CpFailoverChecks.policy(FW_STAT).installedAt(), cp=CpFailoverChecks.cpstatPolicy(CPSTAT).installedAt();
            int routes=CpFailoverChecks.routing(Fixtures.read("cp/failover_routing.txt")).count();
            for (String member:List.of(A,B)) {
                rows.add(new Check("pre",member,null,3,"PASS","{\"up\":1,\"required\":1}",Instant.EPOCH));
                rows.add(new Check("pre",member,null,8,"PASS","{\"bytesPerSecond\":200.0,\"selectedCount\":1,\"reason\":\"NONE\"}",Instant.EPOCH));
                rows.add(new Check("pre",member,null,10,"PASS","{\"fwStat\":{\"firstInstalledAt\":\""+fw+"\",\"secondInstalledAt\":\""+fw
                    +"\"},\"cpstat\":{\"firstInstalledAt\":\""+cp+"\",\"secondInstalledAt\":\""+cp+"\"}}",Instant.EPOCH));
                rows.add(new Check("pre",member,null,14,"PASS","{\"routeCount\":"+routes+",\"defaultRoute\":true}",Instant.EPOCH));
            }
            return rows;
        }
        @Override public Dispatch prepareResumeDispatch(String id,long epoch,int index,String member,String gate,String actionClass) {
            assertEquals("cp_failover_up",gate); assertEquals(A,member); assertEquals("operational-state-change",actionClass);
            prepared++;
            return new Dispatch("nonce-1","run-1","job-r",epoch);
        }
        @Override public boolean dispatchResume(Dispatch intent,java.util.function.Supplier<Boolean> send) {
            beforeSend.run();
            // The real store has already stopped the resume; here the worker's own stop must carry the same code.
            if (refusedInSend!=null) { notSent=true; throw new NotSent(refusedInSend); }
            boolean result;
            try { result=send.get(); }
            catch (NotSent cancelled) { notSent=true; state="STOPPED"; outcome="MUTATION_DISABLED_BEFORE_SEND"; throw cancelled; }
            replies++;
            return result;
        }
        @Override public boolean confirmDispatch(Dispatch intent) { observations++; return true; }
        @Override public void resumeCommand(String id,String gateId) { ledger.add(gateId); }
        @Override public void check(String id,String phase,String member,String vs,int no,String status,String derived) {
            assertEquals("run-1",id);
            checks.add(phase+":"+no+":"+status);
        }
        @Override public Optional<Check> previousReadinessSync(String run,String member,String vs) { return Optional.empty(); }
        // The run row stays STOPPED: none of the run's own write paths may be used by a resume.
        @Override public String mutationAdmission(String id,String cluster,String vs,String vendor,java.util.Set<String> m,boolean p,String j,long e) { throw new AssertionError("run admission"); }
        @Override public boolean workerState(String id,long e,String s,String st,String o,String f,String m) { throw new AssertionError("run state"); }
        @Override public void state(String id,String s,String st,String o,String f,String m) { throw new AssertionError("run state"); }
        @Override public Dispatch prepareDispatch(String id,long e,int i,String m,String g,String a) { throw new AssertionError("run dispatch"); }
        @Override public boolean dispatch(Dispatch intent,java.util.function.Supplier<Boolean> send) { throw new AssertionError("run dispatch"); }
        @Override public void command(String id,String gateId) { throw new AssertionError("run ledger"); }
        @Override public boolean windowValid(String id) { throw new AssertionError("run approval window"); }
    }

    /** The simulated cluster: before UP the former active (A) is Down, after UP it is Standby; the peer stays Active. */
    private static final class Script {
        boolean up, lostReply, untrusted, wrongIdentity, opposedPeer, unrecognized, preempt, stuck, lowTraffic, changedInstall;
        String peerRoleBefore="Active", memberRoleBefore="Down", suffix="(Active Up)";
        int upCount, connectCount, disconnectCount, statReads;
        long bytesA=1000, bytesB=1000;
        final List<String> commands=new ArrayList<>(), targets=new ArrayList<>();
        DeviceTransport transport() {
            return (DeviceTransport)Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[]{DeviceTransport.class},(proxy,method,args) -> {
                    if (method.getName().equals("connect")) {
                        connectCount++;
                        if (untrusted) return new ConnectResult.HostKeyRejected("HOST_KEY_NOT_TRUSTED");
                        ConnectionTarget target=(ConnectionTarget)args[0];
                        return new ConnectResult.Authenticated(new TransportSession() {
                            public String sessionId() { return target.host(); }
                            public Optional<String> presentedIdentity() {
                                return Optional.of(wrongIdentity?"synthetic-other-key"
                                    :target.host().endsWith("11")?"synthetic-key-a":"synthetic-key-b");
                            }
                        });
                    }
                    if (method.getName().equals("disconnect")) { disconnectCount++; return null; }
                    if (!method.getName().equals("exec")) throw new AssertionError(method.getName());
                    String session=((TransportSession)args[0]).sessionId();
                    String literal=((ExecSpec)args[1]).command();
                    commands.add(literal); targets.add(session);
                    assertTrue(literal.startsWith("bash -lc '") && literal.endsWith("'"),literal);
                    String cmd=literal.substring("bash -lc '".length(),literal.length()-1);
                    boolean first=session.endsWith("11");
                    if (cmd.equals("clusterXL_admin up")) {
                        up=true; upCount++;
                        return lostReply?new ExecResult.TimedOut():new ExecResult.Completed("ok",0);
                    }
                    if (cmd.contains("clusterXL_admin")) throw new AssertionError("unexpected write "+cmd);
                    if (cmd.equals("cphaprob stat")) {
                        statReads++;
                        if (unrecognized) return new ExecResult.Completed("unrecognized synthetic output",0);
                        String roleA=up?(preempt?"Active":stuck?"Down":"Standby"):memberRoleBefore;
                        String roleB=up?(preempt?"Standby":"Active"):peerRoleBefore;
                        if (opposedPeer && !first) roleB="Down";
                        return new ExecResult.Completed("Cluster Mode: High Availability "+suffix+"\n"
                            +"Number Unique Address Assigned Load State\n"
                            +"1 "+(first?"(local) ":"")+"192.0.2.11 0% "+roleA+"\n"
                            +"2 "+(!first?"(local) ":"")+"192.0.2.12 100% "+roleB+"\n",0);
                    }
                    if (cmd.equals("cphaprob tablestat")) return new ExecResult.Completed(
                        "---- Unique IP's Table ----\nMember Interface IP-Address\n0 1 192.0.2.21\n1 1 192.0.2.22\n",0);
                    if (cmd.equals("cphaprob -a if")) return new ExecResult.Completed(
                        "CCP mode: Automatic\nRequired interfaces: 1\neth0 UP non sync\n",0);
                    if (cmd.equals("arp -an")) return new ExecResult.Completed("? (192.0.2.31) at 02:00:00:00:00:01 [ether] on eth0\n",0);
                    if (cmd.equals("cat /proc/net/dev")) {
                        long delta=lowTraffic && !first?100:1000;
                        long bytes=first?(bytesA+=1000):(bytesB+=delta);
                        return new ExecResult.Completed("Inter-| Receive | Transmit\nface |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n"
                            +"eth0: "+bytes+" 0 0 0 0 0 0 0 100 0 0 0 0 0 0 0\n",0);
                    }
                    if (cmd.equals("cphaprob syncstat")) return new ExecResult.Completed(
                        "Delta Sync Statistics\nSync status: OK\nDrops:\nLost updates................................. 0\n"
                            +"Lost bulk update events...................... 0\n",0);
                    if (cmd.equals("fw stat")) return new ExecResult.Completed(FW_STAT,0);
                    if (cmd.equals("cpstat -f policy fw")) return new ExecResult.Completed(changedInstall
                        ?CPSTAT.replace("12:00:00","13:30:00"):CPSTAT,0);
                    if (cmd.equals("cphaprob -ia list")) return new ExecResult.Completed(Fixtures.read("cp/failover_pnotes_ok.txt"),0);
                    if (cmd.equals("cphaprob show_bond")) return new ExecResult.Completed(Fixtures.read("cp/failover_bonds.txt"),0);
                    if (cmd.equals("cphaprob show_failover")) return new ExecResult.Completed(Fixtures.read("cp/failover_last_event.txt"),0);
                    if (cmd.equals("cpstat os -f routing")) return new ExecResult.Completed(Fixtures.read("cp/failover_routing.txt"),0);
                    throw new AssertionError(cmd);
                });
        }
        List<String> writes() { return commands.stream().filter(c -> c.contains("clusterXL_admin")).toList(); }
        int firstWrite() { return commands.indexOf(UP_LITERAL); }
    }
    private static DeviceSummaryRecord summary(String id) {
        return new DeviceSummaryRecord(id,"gateway","check_point",DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(CLUSTER));
    }
    private static DeviceRepository devices(List<String> members) {
        return (DeviceRepository)Proxy.newProxyInstance(DeviceRepository.class.getClassLoader(),
            new Class<?>[]{DeviceRepository.class},(proxy,method,args) -> switch(method.getName()) {
                case "findMembersByClusterRef" -> members.stream().map(CpFailoverResumeExecutorTest::summary).toList();
                case "find" -> Optional.of(new DeviceRecord((String)args[0],"gateway","check_point","manual",
                    Instant.EPOCH,false,DeviceEnrollmentState.ENROLLED,false,"credential-ref"));
                case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord("endpoint-ref",(String)args[0],"ssh_exec",
                    A.equals(args[0])?"192.0.2.11":"192.0.2.12",Instant.EPOCH));
                case "findConfirmFacts" -> Optional.of(new DeviceConfirmFacts(Optional.empty(),Optional.empty(),
                    Optional.empty(),Optional.empty(),Optional.of(A.equals(args[0])?"synthetic-key-a":"synthetic-key-b"),Optional.empty(),
                    DeviceConfirmFacts.IDENTITY_MISMATCH_NONE,Optional.empty(),Optional.empty(),Optional.of(CLUSTER),
                    Optional.empty(),DeviceConfirmFacts.PEER_FOLLOW_CORROBORATED,Optional.empty()));
                default -> throw new AssertionError(method.getName());
            });
    }
    private static JobLeaseRepository leases() {
        return (JobLeaseRepository)Proxy.newProxyInstance(JobLeaseRepository.class.getClassLoader(),
            new Class<?>[]{JobLeaseRepository.class},(proxy,method,args) -> {
                if (method.getName().equals("transitionState")) return true;
                throw new AssertionError(method.getName());
            });
    }
    private static JobStepAttemptRepository attempts() {
        AtomicInteger id=new AtomicInteger();
        return (JobStepAttemptRepository)Proxy.newProxyInstance(JobStepAttemptRepository.class.getClassLoader(),
            new Class<?>[]{JobStepAttemptRepository.class},(proxy,method,args) -> switch(method.getName()) {
                case "findByJobAndStep" -> List.of();
                case "insertPreContact" -> "attempt-"+id.incrementAndGet();
                case "markBoundaryCrossed","writeOutcome" -> true;
                default -> throw new AssertionError(method.getName());
            });
    }
    private static GateRegistryPort gates() {
        var rows=GateRegistryFixtureLoader.loadFromStream(CpFailoverResumeExecutorTest.class
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }
    private static void run(Store store,Script script) { run(store,script,new FailoverMutationSwitch(true),List.of(A,B)); }
    private static void run(Store store,Script script,FailoverMutationSwitch mutationSwitch,List<String> members) {
        var nanos=new AtomicLong();
        new CpFailoverJobExecutor(store,devices(members),leases(),attempts(),script.transport(),gates(),
            d -> nanos.addAndGet(d.toNanos()),Duration.ZERO,mutationSwitch,nanos::get).execute("job-r",1);
    }

    @Test void returnStepSendsTheRunsOwnUpOnceToTheFormerActiveThenVerifiesAgainstTheBaseline() {
        Store store=new Store(); Script script=new Script();
        run(store,script);
        assertEquals("DONE",store.state); assertEquals("RETURN_VERIFIED",store.message);
        assertEquals(List.of("READING_STATE","RETURNING","POST_RETURN","DONE"),store.transitions);
        // Exactly one write, the unchanged wire literal, to the former active only.
        assertEquals(List.of(UP_LITERAL),script.writes());
        assertEquals("192.0.2.11",script.targets.get(script.firstWrite()));
        assertEquals(1,store.prepared); assertEquals(1,store.replies); assertEquals(1,store.observations);
        // One fresh cphaprob stat per member before UP, and no other command before it.
        assertEquals(List.of("bash -lc 'cphaprob stat'","bash -lc 'cphaprob stat'"),script.commands.subList(0,script.firstWrite()));
        assertEquals(java.util.Set.of("192.0.2.11","192.0.2.12"),java.util.Set.copyOf(script.targets.subList(0,2)));
        // One new session per member, both closed.
        assertEquals(2,script.connectCount); assertEquals(2,script.disconnectCount);
        // Admission at start, then again before the write.
        assertEquals(List.of(true,false),store.admissionAtStart);
        // The post-return battery runs under the run's id, phase post_return only, every blocking check PASS.
        assertFalse(store.checks.isEmpty());
        assertTrue(store.checks.stream().allMatch(c -> c.startsWith("post_return:")),store.checks.toString());
        for (int blocking:List.of(1,2,3,8,9,10,11,12,14))
            assertTrue(store.checks.stream().filter(c -> c.startsWith("post_return:"+blocking+":")).allMatch(c -> c.endsWith(":PASS")),
                blocking+" "+store.checks);
        assertTrue(store.checks.contains("post_return:6:NOT_EVALUATED"));
        assertTrue(store.ledger.stream().noneMatch("cp_failover_up"::equals),"the write is ledgered by its dispatch intent");
        assertTrue(store.ledger.contains("cp_inventory_cphaprob_stat"));
    }

    @Test void everyFreshReadRefusalSendsNoWrite() {
        record Case(String code,java.util.function.Consumer<Script> setup) {}
        for (var c:List.of(
                new Case("RESUME_PEER_NOT_ACTIVE",s -> s.peerRoleBefore="Standby"),
                new Case("RESUME_MEMBER_NOT_DOWN",s -> s.memberRoleBefore="Standby"),
                new Case("RETURN_PREEMPTION_POSSIBLE",s -> s.suffix="(Primary Up)"),
                new Case("RETURN_PREEMPTION_POSSIBLE",s -> s.suffix=""),
                new Case("RESUME_STATE_UNKNOWN",s -> s.opposedPeer=true),
                new Case("RESUME_STATE_UNKNOWN",s -> s.unrecognized=true),
                new Case("IDENTITY_NOT_VERIFIED",s -> s.wrongIdentity=true),
                new Case("TRUSTED_CONNECTION_REQUIRED",s -> s.untrusted=true))) {
            Store store=new Store(); Script script=new Script(); c.setup().accept(script);
            run(store,script);
            assertEquals("STOPPED",store.state,c.code()); assertEquals(c.code(),store.outcome);
            assertEquals(0,script.upCount); assertEquals(0,store.prepared);
            assertTrue(script.writes().isEmpty(),"transport log holds only reads: "+script.commands);
            assertTrue(script.statReads<=2,"one fresh read per member at most before a refusal");
            assertTrue(store.checks.isEmpty());
        }
    }

    @Test void admissionRefusalsAtStartAndBeforeTheWriteSendNothing() {
        for (String code:List.of("RESUME_REQUEST_EXPIRED","INCIDENT_NOT_OPEN","OPEN_INCIDENT","FLEET_MUTATION_ACTIVE",
                "UNRESOLVED_DISPATCH","MEMBERSHIP_CHANGED","RESUME_RETURN_ALREADY_ATTEMPTED","OWNERSHIP_LOST")) {
            Store atStart=new Store(); atStart.admissions=new ArrayList<>(List.of(code));
            Script none=new Script(); run(atStart,none);
            assertEquals(code,atStart.outcome); assertEquals(0,none.connectCount); assertTrue(none.commands.isEmpty());
            Store beforeWrite=new Store(); beforeWrite.admissions=new ArrayList<>(List.of("ADMITTED",code));
            Script reads=new Script(); run(beforeWrite,reads);
            assertEquals(code,beforeWrite.outcome); assertEquals(0,reads.upCount); assertEquals(0,beforeWrite.prepared);
            assertEquals(2,reads.statReads);
        }
    }

    @Test void switchOffMissingBaselineOrChangedMembershipStopsBeforeAnyContact() {
        Store off=new Store(); Script script=new Script();
        run(off,script,new FailoverMutationSwitch(false),List.of(A,B));
        assertEquals(FailoverMutationSwitch.DISABLED,off.outcome); assertEquals(0,script.connectCount);
        Store noBaseline=new Store(); noBaseline.baseline=false; Script second=new Script();
        run(noBaseline,second);
        assertEquals("RESUME_BASELINE_UNAVAILABLE",noBaseline.outcome); assertEquals(0,second.connectCount);
        Store changed=new Store(); Script third=new Script();
        run(changed,third,new FailoverMutationSwitch(true),List.of(A,"FW-TEST-03"));
        assertEquals("MEMBERSHIP_CHANGED",changed.outcome); assertEquals(0,third.connectCount);
    }

    @Test void switchOffAtTheSendRecordsNotSentAndSendsNothing() {
        var enabled=new java.util.concurrent.atomic.AtomicBoolean(true);
        var mutationSwitch=org.mockito.Mockito.mock(FailoverMutationSwitch.class);
        org.mockito.Mockito.when(mutationSwitch.enabled()).thenAnswer(inv -> enabled.get());
        Store store=new Store(); store.beforeSend=() -> enabled.set(false);
        Script script=new Script();
        run(store,script,mutationSwitch,List.of(A,B));
        assertTrue(store.notSent); assertEquals("MUTATION_DISABLED_BEFORE_SEND",store.outcome);
        assertEquals(0,script.upCount);
    }

    @Test void aRefusalInTheSendTransactionStopsWithItsCodeAsNotSent() {
        for (String code:List.of("OPEN_INCIDENT","MEMBERSHIP_CHANGED","UNRESOLVED_DISPATCH","INCIDENT_NOT_OPEN")) {
            Store store=new Store(); store.refusedInSend=code; Script script=new Script();
            run(store,script);
            assertTrue(store.notSent,code); assertEquals("STOPPED",store.state,code);
            assertEquals(code,store.outcome,"the stop names the refusal, never OUTCOME_UNCERTAIN");
            assertEquals(0,script.upCount,code); assertEquals(1,store.prepared,code); assertEquals(0,store.observations,code);
            assertTrue(store.checks.isEmpty(),code);
        }
    }

    @Test void aLostReplyIsUncertainAndNeverResent() {
        Store store=new Store(); Script script=new Script(); script.lostReply=true;
        run(store,script);
        assertEquals("STOPPED",store.state); assertEquals("OUTCOME_UNCERTAIN",store.outcome);
        assertEquals(1,script.upCount); assertEquals(1,store.prepared); assertEquals(0,store.observations);
        assertTrue(store.checks.isEmpty());
    }

    @Test void preemptionTimeoutAndBlockingPostReturnFailuresStopWithTheirCodes() {
        Store preempted=new Store(); Script preempt=new Script(); preempt.preempt=true;
        run(preempted,preempt);
        assertEquals("UNEXPECTED_ROLES",preempted.outcome); assertEquals(1,preempt.upCount);
        Store timedOut=new Store(); Script stuck=new Script(); stuck.stuck=true;
        run(timedOut,stuck);
        assertEquals("RETURN_TIMEOUT",timedOut.outcome); assertEquals(1,stuck.upCount);
        // 2 fresh reads + at most 20 polls per member.
        assertEquals(2+2*CpFailoverJobExecutor.RESUME_MAX_POLLS,stuck.statReads);
        Store policy=new Store(); Script reinstalled=new Script(); reinstalled.changedInstall=true;
        run(policy,reinstalled);
        assertEquals("POLICY_NOT_MATCHED",policy.outcome); assertTrue(policy.checks.contains("post_return:10:FAIL"));
        Store traffic=new Store(); Script low=new Script(); low.lowTraffic=true;
        run(traffic,low);
        assertEquals("TRAFFIC_BELOW_TOLERANCE",traffic.outcome); assertTrue(traffic.checks.contains("post_return:8:FAIL"));
        for (Store stopped:List.of(preempted,timedOut,policy,traffic)) {
            assertEquals("STOPPED",stopped.state); assertEquals(1,stopped.prepared);
        }
    }
}
