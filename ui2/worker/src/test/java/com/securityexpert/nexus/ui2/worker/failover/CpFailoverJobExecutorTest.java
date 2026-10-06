package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** No socket is opened: responses come from a two-member scripted transport. */
class CpFailoverJobExecutorTest {
    private static final String CLUSTER="CLS-TEST-01", A="FW-TEST-01", B="FW-TEST-02";
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
    private static final class Store extends JooqCpFailoverRepository {
        String state="PLANNED",outcome,stopCode; boolean valid=true; final List<String> checks=new ArrayList<>();
        final List<String> derivedValues=new ArrayList<>();
        String vsId,kind="FAILOVER";
        boolean incidentRequired;
        Optional<Check> previous=Optional.empty();
        @Override public Optional<Check> previousReadinessSync(String run,String member,String vs) {
            CpFailoverJobExecutorTest.check(java.util.Objects.equals(vsId,vs));
            return previous;
        }
        Store() { super(new TransactionBoundary() {
            public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext,T> ignored) {
                throw new AssertionError("Database access in fake test");
            }
        }); }
        @Override public Optional<Run> runByJob(String id) {
            return Optional.of(new Run("run-1",CLUSTER,vsId,"READINESS".equals(kind)?null:"approval-1","actor-1",
                Instant.now(),id,state,state,outcome,null,null,"check_point",kind));
        }
        String admission="ADMITTED", writeAdmission="ADMITTED", returnAdmission="ADMITTED";
        String failBeforeStep, failAfterStep;
        int prepared, replies, observations;
        int admissionCount;
        @Override public String mutationAdmission(String id,String cluster,String vs,String vendor,
                java.util.Set<String> members,boolean possibleSend,String jobId,long epoch) {
            admissionCount++;
            CpFailoverJobExecutorTest.check(CLUSTER.equals(cluster) && java.util.Set.of(A,B).equals(members));
            return admissionCount==1 ? admission : admissionCount==2 ? writeAdmission : returnAdmission;
        }
        @Override public boolean windowValid(String id) { return valid; }
        @Override public void state(String id,String next,String step,String result,String failed,String message) {
            state=next; outcome=result; stopCode=message;
        }
        @Override public boolean workerState(String id,long epoch,String next,String step,String result,String failed,String message) {
            incidentRequired |= "STOPPED".equals(next) && prepared>0;
            state(id,next,step,result,failed,message); return true;
        }
        @Override public Dispatch prepareDispatch(String id,long epoch,int index,String member,String gate,String actionClass) {
            if (state.equals(failBeforeStep)) throw new IllegalStateException("SYNTHETIC_DB_FAILURE");
            prepared++;
            return new Dispatch(state,id,"job-1",epoch);
        }
        @Override public boolean dispatch(Dispatch intent,java.util.function.Supplier<Boolean> send) {
            CpFailoverJobExecutorTest.check(prepared==replies+1);
            boolean result;
            try { result=send.get(); } catch (RuntimeException failure) { result=false; }
            if (state.equals(failAfterStep)) throw new IllegalStateException("SYNTHETIC_DB_FAILURE");
            replies++; return result;
        }
        @Override public boolean confirmDispatch(Dispatch intent) { observations++; return true; }
        @Override public void command(String id,String gateId) {}
        @Override public void check(String id,String phase,String member,String vs,int no,String status,String derived) {
            checks.add(phase+":"+no+":"+status);
            derivedValues.add(derived);
        }
    }
    @Test void admissionRefusesBeforeAnyTransport() {
        for (String code:List.of("OPEN_INCIDENT","WRONG_UNIT","MEMBER_SET_CHANGED","FLEET_MUTATION_ACTIVE")) {
            Store store=new Store(); store.admission=code;
            Script script=new Script(); run(store,script);
            org.junit.jupiter.api.Assertions.assertEquals(code,store.outcome);
            check(script.downCount==0 && script.upCount==0);
            check(store.checks.isEmpty() && store.admissionCount==1);
        }
    }

    @Test void admissionIsRecheckedAfterReadinessBeforeTheWrite() {
        Store store=new Store(); store.writeAdmission="MEMBER_SET_CHANGED";
        Script script=new Script(); run(store,script);
        org.junit.jupiter.api.Assertions.assertEquals("MEMBER_SET_CHANGED",store.outcome);
        check(script.downCount==0 && script.upCount==0 && store.admissionCount==2);
    }

    @Test void expiryRevocationAndOwnershipAreRecheckedBeforeBothWrites() {
        for (String refusal:List.of("WINDOW_EXPIRED","APPROVAL_REVOKED","OWNERSHIP_LOST","OPEN_INCIDENT")) {
            Store beforeDown=new Store(); beforeDown.writeAdmission=refusal;
            Script noWrite=new Script(); run(beforeDown,noWrite);
            org.junit.jupiter.api.Assertions.assertEquals(refusal,beforeDown.outcome);
            check(noWrite.downCount==0 && noWrite.upCount==0);
            Store beforeUp=new Store(); beforeUp.returnAdmission=refusal;
            Script downOnly=new Script(); run(beforeUp,downOnly);
            org.junit.jupiter.api.Assertions.assertEquals(refusal,beforeUp.outcome);
            check(downOnly.downCount==1 && downOnly.upCount==0);
        }
    }

    private static final class Script {
        String lostReplyStep, runtimeErrorStep;
        boolean down,up,stuck,badPre,badPost,badSync,badPolicy,changedPolicyPost;
        boolean readyMember,badPnotes,badBonds,badRoutes,missingDefault,recentFailover;
        String unknownCommand, failedCommand;
        boolean extraTableRows,reverseActive,observerLocalIndices;
        boolean wrongIdentity,missingIdentity,opposedPeer,sameObserver,swapLocalIdsAfterDown;
        String reportedMode;
        String peerPolicyTime;
        boolean zeroBaseline,preempt,extraPnote,adminOnNewActive,adminAfterReturn;
        String trafficFaultPhase,trafficFault,cpstatFaultPhase,cpstatFault;
        int samplesA,samplesB;
        String phase() { return up?"post_return":down?"post":"pre"; }
        String prefix="",lineEnding="\n";
        Map<String,String> measured=Map.of();
        int downCount,upCount,connectCount;
        final List<String> commands=new ArrayList<>();
        long bytesA=1000,bytesB=1000;
        ExecResult writeReply(String step) {
            if (step.equals(runtimeErrorStep)) throw new IllegalStateException("SYNTHETIC_TRANSPORT_FAILURE");
            return step.equals(lostReplyStep)?new ExecResult.TimedOut():new ExecResult.Completed("ok",0);
        }
        DeviceTransport transport() {
            var base=baseTransport();
            return (DeviceTransport)Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[]{DeviceTransport.class},(proxy,method,args) -> {
                    Object result=method.invoke(base,args);
                    if(!method.getName().equals("exec") || !(result instanceof ExecResult.Completed c)) return result;
                    String literal=((ExecSpec)args[1]).command();
                    String output=c.output();
                    for(var entry:measured.entrySet()) if(literal.endsWith(entry.getKey()+"'")) {
                        output=entry.getValue();
                        if(entry.getKey().equals("cphaprob stat") && !((TransportSession)args[0]).sessionId().endsWith("11"))
                            output=output.replace("1 (local)","1        ").replace("2          ","2 (local)  ");
                    }
                    if(measured.containsKey("cphaprob -a if") && !measured.containsKey("cat /proc/net/dev")
                            && literal.endsWith("cat /proc/net/dev'")) output=output.replace("eth0:","bond1.3843:");
                    if(peerPolicyTime!=null && literal.endsWith("fw stat'") && !((TransportSession)args[0]).sessionId().endsWith("11"))
                        output=output.replace("14:01:25",peerPolicyTime);
                    if(reverseActive && literal.endsWith("cphaprob stat'")) output=output
                        .replace("% Active","% TEMP").replace("% Standby","% Active").replace("% TEMP","% Standby");
                    if(literal.endsWith("cphaprob stat'")) {
                        if(reportedMode!=null) output=output.replace("High Availability",reportedMode);
                        if(swapLocalIdsAfterDown && down) output=output.replaceAll("(?m)^1 ","TEMP ").replaceAll("(?m)^2 ","1 ").replaceAll("(?m)^TEMP ","2 ");
                        if(opposedPeer && !((TransportSession)args[0]).sessionId().endsWith("11"))
                            output=output.replace("100% Active","100% Down");
                    }
                    if(literal.endsWith("cat /proc/net/dev'") && measured.containsKey("cat /proc/net/dev")) {
                        boolean first=((TransportSession)args[0]).sessionId().endsWith("11");
                        long offset=(first?samplesA:samplesB)*1000L;
                        var matcher=java.util.regex.Pattern.compile("(?m)^([ \t]*[A-Za-z0-9_.:-]+:[ \t]*)([0-9]+)").matcher(output);
                        output=matcher.replaceAll(m -> m.group(1)+(Long.parseLong(m.group(2))+offset));
                    }
                    output=prefix+output;
                    if(lineEnding.equals("\r\n")) output=output.replace("\r\n","\n").replace("\n",lineEnding);
                    return new ExecResult.Completed(output,c.exitStatus());
                });
        }
        DeviceTransport baseTransport() {
            return (DeviceTransport)Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[]{DeviceTransport.class},(proxy,method,args) -> {
                    if(method.getName().equals("connect")) {
                        connectCount++;
                        ConnectionTarget target=(ConnectionTarget)args[0];
                        return new ConnectResult.Authenticated(new TransportSession() {
                            public String sessionId() { return sameObserver?"192.0.2.11":target.host(); }
                            public Optional<String> presentedIdentity() {
                                return missingIdentity?Optional.empty():Optional.of(wrongIdentity?"synthetic-other-key":
                                    target.host().endsWith("11")?"synthetic-key-a":"synthetic-key-b");
                            }
                        });
                    }
                    if(method.getName().equals("disconnect")) return null;
                    if(!method.getName().equals("exec")) throw new AssertionError(method.getName());
                    String member=((TransportSession)args[0]).sessionId();
                    String literal=((ExecSpec)args[1]).command();
                    commands.add(literal);
                    String cmd=literal.startsWith("bash -lc 'vsenv 12 && ")
                        ?literal.substring("bash -lc 'vsenv 12 && ".length(),literal.length()-1)
                        :literal.startsWith("bash -lc '")?literal.substring("bash -lc '".length(),literal.length()-1):literal;
                    if (cmd.equals(failedCommand)) return new ExecResult.Completed("",1);
                    if (cmd.equals(unknownCommand)) return new ExecResult.Completed("unrecognized synthetic output",0);
                    boolean first=member.endsWith("11");
                    if(cmd.endsWith("clusterXL_admin down")) {down=true;downCount++;return writeReply("FAILING_OVER");}
                    if(cmd.endsWith("clusterXL_admin up")) {up=true;upCount++;return writeReply("RETURNING");}
                    if(cmd.endsWith("cphaprob stat")) {
                        String roleA=up?(preempt?"Active":"Standby"):down&&!stuck?"Down":"Active";
                        String roleB=up&&preempt?"Standby":readyMember?"READY":down&&!stuck?"Active":"Standby";
                        String text="Cluster Mode: High Availability (Active Up)\nNumber Unique Address Assigned Load State\n"
                            +"1 "+(first?"(local) ":"")+"192.0.2.11 100% "+roleA+"\n"
                            +"2 "+(!first?"(local) ":"")+"192.0.2.12 0% "+roleB+"\n";
                        return new ExecResult.Completed(text,0);
                    }
                    if(cmd.endsWith("cphaprob tablestat")) {
                        String text="---- Unique IP's Table ----\nMember Interface IP-Address\n"
                            +"0 1 192.0.2.21\n1 1 "+(badPre&&!first&&!down?"192.0.2.99":"192.0.2.22")+"\n";
                        if(observerLocalIndices) text+="0 "+(first?"6":"3")+" 198.51.100.21\n1 "+(first?"6":"3")+" 198.51.100.22\n";
                        if(extraTableRows && first) text+="0 2 198.51.100.21\n1 2 198.51.100.22\n";
                        return new ExecResult.Completed(text,0);
                    }
                    if(cmd.endsWith("cphaprob -a if")) return new ExecResult.Completed(
                        "CCP mode: Automatic\nRequired interfaces: 1\neth0 UP non sync\n",0);
                    if(cmd.endsWith("arp -an")) return new ExecResult.Completed(
                        "? (192.0.2.31) at 02:00:00:00:00:01 [ether] on eth0\n",0);
                    if(cmd.endsWith("cat /proc/net/dev")) {
                        int sample=first?++samplesA:++samplesB;
                        long delta=zeroBaseline && !down?0:1000;
                        if(phase().equals(trafficFaultPhase) && !first) {
                            if("missing".equals(trafficFault)) return new ExecResult.Completed("unrecognized",0);
                            if("low".equals(trafficFault)) delta=499;
                            if("reset".equals(trafficFault) && sample%2==0) delta=-1000;
                        }
                        if(badPost && down && !first) return new ExecResult.Completed("unrecognized",0);
                        long bytes=first?(bytesA+=delta):(bytesB+=delta);
                        return new ExecResult.Completed("Inter-| Receive | Transmit\nface |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n"
                            +"eth0: "+bytes+" 0 0 0 0 0 0 0 100 0 0 0 0 0 0 0\n",0);
                    }
                    if(cmd.endsWith("cphaprob syncstat")) return new ExecResult.Completed(
                        "Delta Sync Statistics\nSync status: OK\nDrops:\nLost updates................................. "
                            +(badSync&&!first&&!down?"1":"0")+"\nLost bulk update events...................... 0\nSent reject notifications.................... 4554\nReceived reject notifications................ 0\n",0);
                    if(cmd.endsWith("fw stat")) return new ExecResult.Completed(
                        "HOST POLICY DATE\nlocalhost "+((badPolicy&&!first&&!down || changedPolicyPost&&down)?"Other_Policy":"Sample_Policy")
                            +" 10Sep2018 14:01:25 : [>eth0]\n",0);
                    if(cmd.equals("cpstat -f policy fw")) {
                        boolean fault=phase().equals(cpstatFaultPhase);
                        if(fault && "missing".equals(cpstatFault)) return new ExecResult.Completed("Install time:\n",0);
                        String policy=fault && ("changed".equals(cpstatFault) || !first && "mismatch".equals(cpstatFault))
                            ?"Other_Policy":"Sample_Policy";
                        String time=fault && !first && "skew".equals(cpstatFault)?"12:10:01":"12:00:00";
                        return new ExecResult.Completed("Policy name: "+policy+"\nInstall time: 2026-10-06 "+time+"\n",0);
                    }
                    if(cmd.equals("cphaprob -ia list")) {
                        if(badPnotes) return new ExecResult.Completed(Fixtures.read("cp/failover_pnotes_problem.txt"),0);
                        if(down && (!up && (first || adminOnNewActive) || up && first && adminAfterReturn))
                            return new ExecResult.Completed("Device Name: admin_down\nCurrent state: problem\n"
                                +(extraPnote?"Device Name: fwd\nCurrent state: problem\n":""),0);
                        return new ExecResult.Completed(Fixtures.read("cp/failover_pnotes_ok.txt"),0);
                    }
                    if(cmd.equals("cphaprob show_bond")) return new ExecResult.Completed(
                        Fixtures.read("cp/failover_bonds.txt").replace("|UP    |",badBonds?"|UP!   |":"|UP    |"),0);
                    if(cmd.equals("cphaprob show_failover")) {
                        String output=Fixtures.read("cp/failover_last_event.txt");
                        if(recentFailover) output="Event time: "+java.time.format.DateTimeFormatter
                            .ofPattern("EEE MMM d HH:mm:ss uuuu",java.util.Locale.ENGLISH)
                            .withZone(java.time.ZoneId.of("Europe/Istanbul")).format(Instant.now().minusSeconds(60));
                        return new ExecResult.Completed(output,0);
                    }
                    if(cmd.equals("cpstat os -f routing")) {
                        String routes=Fixtures.read("cp/failover_routing.txt");
                        // Standby pre-check may differ: post-check must use the former active's baseline.
                        if(!down&&!first || down&&!first&&badRoutes) routes=routes.replace(
                            "|0.0.0.0|0.0.0.0|198.51.100.1|eth2-01|\n", "");
                        if(down&&!first&&missingDefault) routes=routes.replace("|0.0.0.0|0.0.0.0|", "|192.0.2.0|255.255.255.0|");
                        return new ExecResult.Completed(routes,0);
                    }
                    throw new AssertionError(cmd);
                });
        }
    }
    private static DeviceSummaryRecord summary(String id) {
        return new DeviceSummaryRecord(id,"gateway","check_point",DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(CLUSTER));
    }
    private static DeviceRepository devices() {
        return devices(true);
    }
    private static DeviceRepository devices(boolean recordedIdentity) {
        return (DeviceRepository)Proxy.newProxyInstance(DeviceRepository.class.getClassLoader(),
            new Class<?>[]{DeviceRepository.class},(proxy,method,args) -> switch(method.getName()) {
                case "findMembersByClusterRef" -> List.of(summary(A),summary(B));
                case "find" -> Optional.of(new DeviceRecord((String)args[0],"gateway","check_point","manual",
                    Instant.EPOCH,false,DeviceEnrollmentState.ENROLLED,false,"credential-ref"));
                case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord("endpoint-ref",(String)args[0],"ssh_exec",
                    A.equals(args[0])?"192.0.2.11":"192.0.2.12",Instant.EPOCH));
                case "findConfirmFacts" -> Optional.of(new DeviceConfirmFacts(Optional.empty(),Optional.empty(),
                    Optional.empty(),Optional.empty(),recordedIdentity?Optional.of(A.equals(args[0])?"synthetic-key-a":"synthetic-key-b"):Optional.empty(),Optional.empty(),
                    DeviceConfirmFacts.IDENTITY_MISMATCH_NONE,Optional.empty(),Optional.empty(),Optional.of(CLUSTER),
                    Optional.empty(),DeviceConfirmFacts.PEER_FOLLOW_CORROBORATED,Optional.empty()));
                default -> throw new AssertionError(method.getName());
            });
    }
    private static JobLeaseRepository leases() {
        return (JobLeaseRepository)Proxy.newProxyInstance(JobLeaseRepository.class.getClassLoader(),
            new Class<?>[]{JobLeaseRepository.class},(proxy,method,args) -> {
                if(method.getName().equals("transitionState")) return true;
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
        var rows=GateRegistryFixtureLoader.loadFromStream(CpFailoverJobExecutorTest.class
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }
    private static void run(Store store,Script script) {
        var nanos=new java.util.concurrent.atomic.AtomicLong();
        new CpFailoverJobExecutor(store,devices(),leases(),attempts(),script.transport(),gates(),d -> nanos.addAndGet(d.toNanos()),
            Duration.ZERO,new FailoverMutationSwitch(!"READINESS".equals(store.kind)),nanos::get)
            .execute("job-1",1);
    }
    @Test void everyWriteStopsAtPersistenceAndDeliveryFailuresWithoutReplay() {
        for (String step:List.of("FAILING_OVER","RETURNING")) {
            for (String fault:List.of("before","after","lost","runtime")) {
                Store store=new Store(); Script script=new Script();
                switch (fault) {
                    case "before" -> store.failBeforeStep=step;
                    case "after" -> store.failAfterStep=step;
                    case "lost" -> script.lostReplyStep=step;
                    case "runtime" -> script.runtimeErrorStep=step;
                }
                run(store,script);
                check("STOPPED".equals(store.state));
                check(script.downCount==("FAILING_OVER".equals(step)&&"before".equals(fault)?0:1));
                check(script.upCount==("RETURNING".equals(step)&&!"before".equals(fault)?1:0));
                check(store.observations==("RETURNING".equals(step)?1:0));
            }
        }
    }
    @Test void disabledSwitchStopsBeforeTransportOrAttempts() {
        Store store=new Store(); Script script=new Script();
        new CpFailoverJobExecutor(store,devices(),leases(),attempts(),script.transport(),gates(),d -> {},
            Duration.ZERO,new FailoverMutationSwitch(false)).execute("job-1",1);
        check("STOPPED".equals(store.state));
        check(FailoverMutationSwitch.DISABLED.equals(store.outcome));
        check(script.connectCount==0 && script.commands.isEmpty());
        check(script.downCount==0 && script.upCount==0);
    }
    @Test void unverifiedEndpointIdentityStopsBeforeAnyCommand() {
        for(boolean missing:List.of(false,true)) {
            Store store=new Store(); Script script=new Script();
            script.missingIdentity=missing; script.wrongIdentity=!missing;
            run(store,script);
            check(store.state.equals("STOPPED") && store.stopCode.equals("IDENTITY_NOT_VERIFIED"));
            check(script.commands.isEmpty());
        }
    }
    @Test void missingRecordedIdentityContinuesOnlyTheReadinessBattery() {
        for(boolean readiness:List.of(false,true)) {
            Store store=new Store(); store.kind=readiness?"READINESS":"FAILOVER";
            Script script=new Script();
            var nanos=new java.util.concurrent.atomic.AtomicLong();
            new CpFailoverJobExecutor(store,devices(false),leases(),attempts(),script.transport(),gates(),d -> nanos.addAndGet(d.toNanos()),
                Duration.ZERO,new FailoverMutationSwitch(!readiness),nanos::get).execute("job-1",1);
            assertEquals(readiness?"UNKNOWN":"IDENTITY_NOT_VERIFIED",store.outcome);
            assertEquals(readiness?"IDENTITY_NOT_RECORDED":"IDENTITY_NOT_VERIFIED",store.stopCode);
            assertEquals(0,script.downCount); assertEquals(0,script.upCount);
            if(readiness) {
                assertEquals(24,store.checks.size());
                assertEquals(2,store.checks.stream().filter("pre:1:UNKNOWN"::equals).count());
                assertTrue(store.checks.contains("pre:14:PASS"));
                assertTrue(store.derivedValues.stream().anyMatch(d -> d.contains("IDENTITY_NOT_RECORDED")));
            } else assertEquals(0,script.connectCount);
        }
    }
    @Test void readinessStillBlocksMismatchedOrUnpresentedIdentity() {
        for(boolean missing:List.of(false,true)) {
            Store store=new Store(); store.kind="READINESS";
            Script script=new Script(); script.missingIdentity=missing; script.wrongIdentity=!missing;
            run(store,script);
            assertEquals("UNKNOWN",store.outcome);
            assertEquals("IDENTITY_NOT_VERIFIED",store.stopCode);
            assertTrue(script.commands.isEmpty());
        }
    }
    @Test void unsupportedModesAreUnknownAndNeverUnhealthyInReadiness() {
        for(String mode:List.of("Load Sharing Unicast","Virtual System Load Sharing")) {
            Store store=new Store(); store.kind="READINESS";
            Script script=new Script(); script.reportedMode=mode;
            run(store,script);
            assertEquals("UNKNOWN",store.outcome);
            assertEquals(2,store.checks.stream().filter("pre:1:UNKNOWN"::equals).count());
            assertTrue(store.checks.stream().noneMatch(s -> s.endsWith(":FAIL")));
            assertTrue(store.derivedValues.stream().anyMatch(d -> d.contains("UNSUPPORTED_MODE")));
        }
    }
    @Test void perVsVslsLabelSupportsReadinessSwitchAndReturn() {
        for(boolean readiness:List.of(false,true)) {
            Store store=new Store(); store.vsId="12"; store.kind=readiness?"READINESS":"FAILOVER";
            Script script=new Script(); script.reportedMode="Virtual System Load Sharing";
            script.prefix="Context is set to Virtual Device VS-SYNTHETIC (ID 12).\n";
            run(store,script);
            assertEquals(readiness?"READY":"COMMAND_GATE_UNAVAILABLE",store.outcome);
            assertEquals(0,script.downCount); assertEquals(0,script.upCount);
            assertTrue(script.commands.stream().allMatch(c -> c.startsWith("bash -lc 'vsenv 12 && ")));
        }
    }
    @Test void localIdsCannotRebindToTheOtherSessionAfterSwitch() {
        Store store=new Store(); Script script=new Script(); script.swapLocalIdsAfterDown=true;
        run(store,script);
        check(store.state.equals("STOPPED"));
        check(script.downCount==1 && script.upCount==0);
    }
    @Test void reciprocalAndModeFailuresNeverDispatchWrites() {
        for(String failure:List.of("opposed","observer","vsls","unknown","wrong-vs","chassis")) {
            Store store=new Store(); Script script=new Script();
            script.opposedPeer=failure.equals("opposed"); script.sameObserver=failure.equals("observer");
            if(failure.equals("vsls")) script.reportedMode="Virtual System Load Sharing";
            if(failure.equals("unknown")) script.reportedMode="Unknown";
            if(failure.equals("wrong-vs")) {store.vsId="12"; script.prefix="Context is set to Virtual Device VS-SYNTHETIC (ID 012).\n";}
            if(failure.equals("chassis")) {
                store.vsId="12";
                script.measured=Map.of("cphaprob stat",Fixtures.read("cp/failover_stat_vsx_chassis.txt")
                    .replace("Virtual System Load Sharing","High Availability"));
            }
            run(store,script);
            check(store.state.equals("STOPPED")); check(script.downCount==0 && script.upCount==0);
        }
    }
    @Test void measuredOutputsThroughReadStepAndVsUsesOwnActiveMember() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String shape:List.of("vs0","vs1","gateway")) for(boolean banner:List.of(false,true)) {
            Store store=new Store(); store.kind="READINESS";
            if(!shape.equals("gateway")) store.vsId="12";
            Script script=new Script();
            script.measured=json.readValue(Fixtures.read("cp/readiness_measured_"+shape+".json"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {});
            script.prefix=(store.vsId==null?"":"Context is set to Virtual Device VS-SYNTHETIC (ID 12).\n")
                +(banner?"Warning! Synthetic login notice.\n":"");
            run(store,script);
            String context=shape+" banner="+banner+" checks="+store.checks;
            assertEquals(shape.equals("vs0")?"UNKNOWN":"READY",store.outcome,context);
            assertEquals(24,store.checks.size(),"All readiness checks must be recorded: "+context);
            assertTrue(script.downCount==0 && script.upCount==0,"Readiness must not dispatch writes: "+context);
        }
        Store store=new Store(); store.kind="READINESS"; store.vsId="12";
        Script script=new Script(); script.reverseActive=true;
        run(store,script);
        assertEquals(2,store.checks.stream().filter("pre:1:PASS"::equals).count(),
            "Reversed VS roles must retain reciprocal HA evidence: "+store.checks);
        assertTrue(store.checks.contains("pre:6:NOT_EVALUATED"),"VS session continuity check 6: "+store.checks);
        assertEquals("READY",store.outcome,"VS readiness with reversed active member: "+store.checks);
    }
    @Test void oneSecondPolicySkewPassesParity() {
        Store store=new Store(); store.kind="READINESS";
        Script script=new Script(); script.peerPolicyTime="14:01:26";
        run(store,script);
        check(store.checks.contains("pre:10:PASS") && "READY".equals(store.outcome));
        check(store.derivedValues.stream().anyMatch(d -> d.contains("\"installSkewSeconds\":1")
            && d.contains("firstInstalledAt") && d.contains("secondInstalledAt")));
    }
    @Test void policySkewOverTenMinutesFailsWithReason() {
        Store store=new Store(); store.kind="READINESS";
        Script script=new Script(); script.peerPolicyTime="14:11:26";
        run(store,script);
        check(store.checks.contains("pre:10:FAIL") && "NOT_READY".equals(store.outcome));
        check(store.derivedValues.stream().filter(d -> d.contains("INSTALL_TIMES_DIFFER")
            && d.contains("\"installSkewSeconds\":601")).count()==2);
        check(script.downCount==0 && script.upCount==0);
    }
    @Test void sessionContinuityIsInformationalAndNeverCollected() {
        for(boolean readiness:List.of(false,true)) {
            Store store=new Store(); store.kind=readiness?"READINESS":"FAILOVER";
            Script script=new Script(); run(store,script);
            assertEquals(readiness?"READY":"SUCCEEDED",store.outcome);
            assertTrue(script.commands.stream().noneMatch(c -> c.contains("fw tab")));
            assertEquals(readiness?2:6,store.derivedValues.stream()
                .filter(d -> d.contains("SESSION_CONTINUITY_NOT_EVALUATED")).count());
            assertTrue(store.checks.stream().filter(c -> c.contains(":6:")).allMatch(c -> c.endsWith(":NOT_EVALUATED")));
        }
    }
    @Test void readinessUsesStoredCounterBaselineAndRecordsIncrease() {
        Store store=new Store(); store.kind="READINESS";
        store.previous=Optional.of(new JooqCpFailoverRepository.Check("pre",A,null,9,"PASS",
            "{\"lostUpdates\":0,\"lostBulkUpdateEvents\":0}",Instant.parse("2026-09-30T12:00:00Z")));
        Script script=new Script(); script.badSync=true;
        run(store,script);
        check(store.checks.contains("pre:9:FAIL"));
        check(store.derivedValues.stream().anyMatch(d -> d.contains("\"lostUpdatesIncrease\":1")));
        check("NOT_READY".equals(store.outcome));
    }
    private static final String LOGIN_WARNING="Warning! Synthetic boot password reminder.\n";
    private static final String VS_CONTEXT="Context is set to Virtual Device VS-SYNTHETIC (ID 12).\n";
    private static List<String> loginPreambles() {
        return List.of("",VS_CONTEXT,LOGIN_WARNING,LOGIN_WARNING+VS_CONTEXT,VS_CONTEXT+LOGIN_WARNING,
            LOGIN_WARNING.replace(".\n",". \t\n")+VS_CONTEXT.replace(".\n",". \t\n"),
            "\n"+LOGIN_WARNING+"\n \t\n"+VS_CONTEXT+"\n"+LOGIN_WARNING+LOGIN_WARNING+"\n");
    }
    @Test void loginWarningReadinessPassesHealthyLfAndCrlfFixtures() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String,String> fixture=json.readValue(Fixtures.read("cp/readiness_login_warning.json"),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {});
        for(String eol:List.of("\n","\r\n")) for(String prefix:loginPreambles()) {
            Store store=new Store(); store.kind="READINESS"; store.vsId="12";
            Script script=new Script(); script.measured=fixture; script.prefix=prefix; script.lineEnding=eol;
            run(store,script);
            org.junit.jupiter.api.Assertions.assertEquals("READY",store.outcome,store.checks.toString());
            for(int no:List.of(3,5,8,10,11,12))
                org.junit.jupiter.api.Assertions.assertEquals(2L,
                    store.checks.stream().filter(c -> c.equals("pre:"+no+":PASS")).count(),"check "+no);
            check(store.derivedValues.stream().filter(d -> d.contains("\"up\":7")
                && d.contains("\"required\":7")).count()==2);
            check(script.downCount==0 && script.upCount==0);
        }
        check(ReadinessShapeLog.textShape(LOGIN_WARNING).contains("[SECRET REDACTED]"));
    }
    @Test void loginWarningReadinessKeepsUnreadableOutputsUnknown() {
        Map<String,Integer> commands=Map.of("cphaprob -a if",3,"arp -an",5,"cat /proc/net/dev",8,
            "fw stat",10,"cphaprob -ia list",11,"cphaprob show_bond",12);
        for(String eol:List.of("\n","\r\n")) for(String prefix:loginPreambles())
            commands.forEach((command,no) -> {
                Store store=new Store(); store.kind="READINESS"; store.vsId="12";
                Script script=new Script(); script.prefix=prefix; script.lineEnding=eol;
                script.measured=Map.of(command,"Error: synthetic unreadable command output\n");
                run(store,script);
                check(store.checks.stream().filter(c -> c.equals("pre:"+no+":UNKNOWN")).count()==2);
                check(script.downCount==0 && script.upCount==0);
            });
    }
    @Test void normalizationPreservesErrorsMismatchedOpaqueVsidsAndBodyWarnings() {
        var shapes=new ReadinessShapeLog("check_point");
        for(String eol:List.of("\n","\r\n")) {
            String body="There are no pnotes in problem state\n";
            for(String prefix:loginPreambles())
                org.junit.jupiter.api.Assertions.assertEquals(body.replace("\n",eol),
                    CpFailoverJobExecutor.normalizeRead("cphaprob -ia list",
                        (prefix+body).replace("\n",eol),"12",shapes));
            for(String prefix:loginPreambles()) {
                String error="Error: synthetic command failed\n";
                String normalized=CpFailoverJobExecutor.normalizeRead("cphaprob -ia list",
                    (prefix+error+VS_CONTEXT+LOGIN_WARNING).replace("\n",eol),"12",shapes);
                check(normalized.contains((error+VS_CONTEXT+LOGIN_WARNING).replace("\n",eol)));
            }
            for(String vsId:List.of("012","13")) {
                String wrong=(VS_CONTEXT+body).replace("\n",eol);
                check(CpFailoverJobExecutor.normalizeRead("cphaprob -ia list",wrong,vsId,shapes).equals(wrong));
            }
            String bodyWarning=(body+LOGIN_WARNING).replace("\n",eol);
            check(CpFailoverJobExecutor.normalizeRead("cphaprob -ia list",bodyWarning,"12",shapes).equals(bodyWarning));
        }
    }
    @Test void readNormalizationRejectsUnprovedBannerAndWrongContext() {
        var shapes=new ReadinessShapeLog("check_point");
        String bad="Warning! Synthetic notice.\nunsupported";
        check(CpFailoverJobExecutor.normalizeRead("fw stat",bad,null,shapes).equals(bad));
        String empty="Warning! Synthetic notice.\n";
        check(CpFailoverJobExecutor.normalizeRead("arp -an",empty,null,shapes).equals(empty));
        String wrong="Context is set to Virtual Device VS-SYNTHETIC (ID 01).\nbody";
        check(CpFailoverJobExecutor.normalizeRead("fw stat",wrong,"1",shapes).equals(wrong));
    }
    @Test void happyPath() {
        Store store=new Store(); Script script=new Script(); run(store,script);
        check(store.state.equals("DONE") && script.downCount==1 && script.upCount==1);
        check(store.checks.stream().anyMatch(s -> s.equals("post:8:PASS")));
        check(store.checks.stream().anyMatch(s -> s.equals("post:9:PASS")));
        check(store.checks.stream().anyMatch(s -> s.equals("post:10:PASS")));
        check(store.derivedValues.stream().anyMatch(d -> d.contains("\"sentRejectNotifications\":4554")));
    }
    @Test void threeTrafficWindowsAreDistinctAndBaselineEndsBeforeDown() {
        Store store=new Store(); Script script=new Script(); run(store,script);
        assertEquals("SUCCEEDED",store.outcome);
        for(String phase:List.of("pre","post","post_return")) {
            assertEquals(2,store.checks.stream().filter((phase+":8:PASS")::equals).count());
            assertEquals(2,store.checks.stream().filter((phase+":10:PASS")::equals).count());
        }
        assertEquals(6,script.samplesA); assertEquals(6,script.samplesB);
        assertEquals(12,script.commands.stream().filter(c -> c.endsWith("cat /proc/net/dev'")).count());
        int down=script.commands.indexOf("bash -lc 'clusterXL_admin down'");
        assertTrue(script.commands.get(down-1).endsWith("cat /proc/net/dev'"));
        assertTrue(script.commands.get(down-2).endsWith("cat /proc/net/dev'"));
        assertEquals(2,store.checks.stream().filter("post_return:9:PASS"::equals).count());
        assertEquals(2,store.checks.stream().filter("post_return:11:PASS"::equals).count());
    }
    @Test void trafficFailureInEveryWindowStopsWithoutRetry() {
        for(String phase:List.of("pre","post","post_return")) for(String fault:List.of("reset","missing","low")) {
            if(phase.equals("pre") && fault.equals("low")) continue;
            Store store=new Store(); Script script=new Script();
            script.trafficFaultPhase=phase; script.trafficFault=fault; run(store,script);
            assertEquals("TRAFFIC_BELOW_TOLERANCE",store.outcome,phase+" "+fault);
            assertTrue(store.checks.contains(phase+":8:"+(fault.equals("low")?"FAIL":"UNKNOWN")));
            assertEquals(phase.equals("pre")?0:1,script.downCount);
            assertEquals(phase.equals("post_return")?1:0,script.upCount);
            assertEquals(!phase.equals("pre"),store.incidentRequired);
        }
        Store store=new Store(); Script script=new Script(); script.zeroBaseline=true; run(store,script);
        assertEquals("TRAFFIC_BELOW_TOLERANCE",store.outcome);
        assertTrue(store.checks.contains("pre:8:UNKNOWN")); assertEquals(0,script.downCount);
    }
    @Test void missingTrafficReplyRecordsUnknownAndStopsBeforeMutation() {
        Store store=new Store(); Script script=new Script(); script.failedCommand="cat /proc/net/dev"; run(store,script);
        assertEquals("TRAFFIC_BELOW_TOLERANCE",store.outcome);
        assertEquals(2,store.checks.stream().filter("pre:8:UNKNOWN"::equals).count());
        assertEquals(0,script.downCount);
    }
    @Test void memberRatesUseSeparateMonotonicIntervals() {
        Store store=new Store(); Script script=new Script();
        long[] times={0,2,10,22,30,32,40,52,60,62,70,82};
        var index=new AtomicInteger();
        new CpFailoverJobExecutor(store,devices(),leases(),attempts(),script.transport(),gates(),d -> {},
            Duration.ZERO,new FailoverMutationSwitch(true),() -> times[index.getAndIncrement()]*1_000_000_000L)
            .execute("job-1",1);
        assertEquals("SUCCEEDED",store.outcome);
        assertEquals(3,store.derivedValues.stream().filter(d -> d.contains("\"bytesPerSecond\":100.0")
            && d.contains("\"elapsedNanos\":10000000000")).count());
        assertEquals(3,store.derivedValues.stream().filter(d -> d.contains("\"bytesPerSecond\":50.0")
            && d.contains("\"elapsedNanos\":20000000000")).count());
    }
    @Test void cpstatPolicyFailsClosedInAllFailoverPhases() {
        for(String phase:List.of("pre","post","post_return")) for(String fault:List.of("mismatch","skew","missing","changed")) {
            if(phase.equals("pre") && fault.equals("changed")) continue;
            Store store=new Store(); Script script=new Script();
            script.cpstatFaultPhase=phase; script.cpstatFault=fault; run(store,script);
            assertEquals("POLICY_NOT_MATCHED",store.outcome,phase+" "+fault);
            assertTrue(store.checks.contains(phase+":10:"+(fault.equals("missing")?"UNKNOWN":"FAIL")));
            assertEquals(phase.equals("pre")?0:1,script.downCount);
            assertEquals(phase.equals("post_return")?1:0,script.upCount);
            assertTrue(store.derivedValues.stream().noneMatch(d -> d.contains("Sample_Policy") || d.contains("Other_Policy")));
        }
    }
    @Test void intentionalAdminDownNeverExcusesOtherMembersOrFaults() {
        for(String fault:List.of("extra","new_active","after_return")) {
            Store store=new Store(); Script script=new Script();
            script.extraPnote=fault.equals("extra"); script.adminOnNewActive=fault.equals("new_active");
            script.adminAfterReturn=fault.equals("after_return"); run(store,script);
            assertEquals("PNOTES_NOT_READY",store.outcome);
            assertEquals(1,script.downCount); assertEquals(fault.equals("after_return")?1:0,script.upCount);
            assertTrue(store.incidentRequired);
        }
    }
    @Test void unexpectedPreemptionStopsAndUsesIncidentBoundary() {
        Store store=new Store(); Script script=new Script(); script.preempt=true; run(store,script);
        assertEquals("UNEXPECTED_ROLES",store.outcome);
        assertEquals(1,script.downCount); assertEquals(1,script.upCount);
        assertTrue(store.incidentRequired);
        assertTrue(store.checks.stream().noneMatch(c -> c.startsWith("post_return:")));
    }
    @Test void precheckFailStopsBeforeWrite() {
        Store store=new Store(); Script script=new Script(); script.badPre=true; run(store,script);
        check(store.state.equals("STOPPED") && script.downCount==0 && script.upCount==0);
        check(store.checks.stream().anyMatch(s -> s.equals("pre:2:FAIL")));
        check(store.checks.size()==4);
        check(script.commands.size()==4);
    }
    @Test void observerLocalIndicesPassInReadinessIncludingVsContexts() {
        for(String vs:List.of("", "12")) {
            Store store=new Store(); store.kind="READINESS"; store.vsId=vs.isEmpty()?null:vs;
            Script script=new Script(); script.observerLocalIndices=true;
            run(store,script);
            check(store.checks.stream().filter(c -> c.equals("pre:2:PASS")).count()==2);
            check(store.derivedValues.stream().filter(d -> d.contains("\"firstEntries\":4")
                && d.contains("\"secondEntries\":4") && d.contains("\"differences\":[]")).count()==2);
            check(script.downCount==0 && script.upCount==0);
        }
    }
    @Test void differingVsAddressSetsFailAndStopWithoutWrites() {
        for(boolean extra:List.of(false,true)) {
            Store store=new Store(); store.kind="READINESS"; store.vsId="12";
            Script script=new Script(); script.badPre=!extra; script.extraTableRows=extra;
            run(store,script);
            check("NOT_READY".equals(store.outcome));
            check(store.checks.contains("pre:2:FAIL"));
            check(store.derivedValues.stream().filter(d -> d.contains("\"reason\":\"tables differ\"")
                && d.contains("\"firstEntries\":"+(extra?4:2)) && d.contains("\"secondEntries\":2")
                && d.contains("MISSING_ON_SECOND")).count()==2);
            check(store.derivedValues.stream().noneMatch(d -> d.contains("192.0.2.") || d.contains("198.51.100.")));
            check(script.downCount==0 && script.upCount==0);
        }
    }

    @Test void readinessCollectsBothMembersAfterFailAndUnknown() {
        for (boolean unknownFirst:List.of(false,true)) {
            Store store=new Store(); store.kind="READINESS";
            Script script=new Script(); script.badPre=!unknownFirst; script.badPnotes=unknownFirst;
            script.unknownCommand="cphaprob -a if";
            run(store,script);
            check("NOT_READY".equals(store.outcome));
            check(store.checks.size()==24);
            check(store.checks.contains("pre:3:UNKNOWN"));
            check(store.checks.stream().filter(c -> c.equals("pre:14:PASS")).count()==2);
            check(script.connectCount==2 && script.commands.size()==24);
            check(script.downCount==0 && script.upCount==0);
        }
    }
    @Test void readinessStopsWhenCommandUnavailable() {
        Store store=new Store(); store.kind="READINESS";
        Script script=new Script(); script.failedCommand="cphaprob tablestat";
        run(store,script);
        check("UNKNOWN".equals(store.outcome) && store.checks.size()==2);
        check(script.commands.size()==3 && script.downCount==0);
        check("COMMAND_UNAVAILABLE".equals(store.stopCode));
    }
    @Test void loginEnvironmentErrorsCarryStopCodeEvenWithZeroExit() {
        for(String error:List.of("cphaprob: command not found","bash: /synthetic/profile: No such file or directory","Permission denied")) {
            Store store=new Store(); store.kind="READINESS";
            Script script=new Script(); script.measured=Map.of("cphaprob stat",error);
            run(store,script);
            check("UNKNOWN".equals(store.outcome));
            check("COMMAND_UNAVAILABLE".equals(store.stopCode));
            check(store.checks.isEmpty() && script.commands.size()==1 && script.downCount==0);
        }
    }
    @Test void arpVsPreambleRetainsCountsIncludingEmptyTables() {
        for(int count:List.of(0,1,2)) {
            Store store=new Store(); store.kind="READINESS"; store.vsId="12";
            Script script=new Script(); script.measured=Map.of("arp -an",
                "Context is set to Virtual Device VS-SYNTHETIC (ID 12).\n"
                +"? (192.0.2.31) at 02:00:00:00:00:01 [ether] on eth0\n".repeat(count));
            run(store,script);
            check("READY".equals(store.outcome));
            check(store.derivedValues.stream().filter(d -> d.equals("{\"count\":"+count+"}")).count()==2);
        }
    }
    @Test void unavailableCommandPreservesStopCodeAfterEarlierBlockingFailure() {
        Store store=new Store(); store.kind="READINESS";
        Script script=new Script(); script.badPre=true; script.failedCommand="arp -an";
        run(store,script);
        check("NOT_READY".equals(store.outcome));
        check("COMMAND_UNAVAILABLE".equals(store.stopCode));
        check(script.downCount==0);
    }
    @Test void unhealthyVslsRunFailsWithEvidenceAndStillCollectsLaterChecks() {
        Store store=new Store(); store.kind="READINESS"; store.vsId="12";
        Script script=new Script(); script.measured=Map.of("cphaprob stat",
            "Cluster Mode: Virtual System Load Sharing (Active Up)\n"
            +"1 (local) 192.0.2.11 0% DOWN\n2          192.0.2.12 100% ACTIVE(!)\nActive PNOTEs: synthetic\n");
        run(store,script);
        check("NOT_READY".equals(store.outcome));
        check(store.checks.contains("pre:1:FAIL") && store.checks.size()==24);
        check(store.derivedValues.get(0).contains("\"local_state\":\"DOWN\""));
        check(store.derivedValues.get(0).contains("\"peer_state\":\"ACTIVE(!)\""));
    }

    @Test void informationalUnknownDoesNotBlockReadiness() {
        Store store=new Store(); store.kind="READINESS";
        Script script=new Script(); script.unknownCommand="cphaprob show_failover";
        run(store,script);
        check("READY".equals(store.outcome) && store.checks.size()==24);
    }
    @Test void readinessCompletesWithoutWriteAndWriteGateRefuses() {
        Store store=new Store(); store.kind="READINESS"; Script script=new Script(); run(store,script);
        check(store.state.equals("DONE") && "READY".equals(store.outcome));
        check(script.downCount==0 && script.upCount==0);
        check(!CpFailoverJobExecutor.writeAllowed("READINESS","clusterXL_admin down"));
        check(!CpFailoverJobExecutor.writeAllowed("READINESS","clusterXL_admin up"));
    }
    @Test void readyMemberFailsReadinessRatherThanReturningUnknown() {
        Store store=new Store(); store.kind="READINESS";
        Script script=new Script(); script.readyMember=true; run(store,script);
        check("NOT_READY".equals(store.outcome));
        check(store.checks.contains("pre:1:FAIL"));
        check(script.downCount==0 && script.upCount==0);
    }
    @Test void unknownOutputFromEveryCheckStaysUnknownWithoutWrites() {
        Map<String,Integer> commands=Map.of("cphaprob stat",1,"cphaprob tablestat",2,"cphaprob -a if",3,
            "cat /proc/net/dev",8,"cphaprob syncstat",9,"fw stat",10);
        commands.forEach((command,no) -> {
            Store store=new Store(); store.kind="READINESS";
            Script script=new Script(); script.unknownCommand=command; run(store,script);
            check((no==5?"READY":"UNKNOWN").equals(store.outcome));
            check(store.checks.contains("pre:"+no+":UNKNOWN"));
            check(script.downCount==0 && script.upCount==0);
        });
    }
    @Test void warningOnlyReadinessOutputsStayUnknownWithoutWrites() {
        Map<String,Integer> commands=Map.of("cphaprob -a if",3,"arp -an",5,"cat /proc/net/dev",8,
            "fw stat",10,"cphaprob -ia list",11,"cphaprob show_bond",12);
        commands.forEach((command,no) -> {
            Store store=new Store(); store.kind="READINESS"; store.vsId="12";
            Script script=new Script();
            script.measured=Map.of(command,"Warning! This is a test sentence with 'set something' in it.");
            run(store,script);
            check((no==5?"READY":"UNKNOWN").equals(store.outcome));
            check(store.checks.contains("pre:"+no+":UNKNOWN"));
            check(script.downCount==0 && script.upCount==0);
        });
    }
    @Test void readyReadinessNeverSkipsFreshFailoverPrecheck() {
        Store store=new Store(); store.kind="READINESS";
        Script script=new Script(); run(store,script);
        check("READY".equals(store.outcome));
        check(script.downCount==0 && script.upCount==0);
        int previousChecks=store.checks.size();
        int previousConnections=script.connectCount;
        store.kind="FAILOVER";
        store.state="PLANNED";
        script.badPre=true;
        run(store,script);
        check(script.connectCount==previousConnections+2);
        check(store.state.equals("STOPPED"));
        check(store.checks.subList(previousChecks,store.checks.size()).contains("pre:2:FAIL"));
        check(script.downCount==0 && script.upCount==0);
    }
    @Test void failoverTimeoutStopsWithoutUp() {
        Store store=new Store(); Script script=new Script(); script.stuck=true; run(store,script);
        check(store.state.equals("STOPPED") && "FAILOVER_TIMEOUT".equals(store.outcome));
        check(script.downCount==1 && script.upCount==0);
    }
    @Test void syncFailureStopsBeforeWrite() {
        Store store=new Store();
        store.previous=Optional.of(new JooqCpFailoverRepository.Check("pre",A,null,9,"PASS",
            "{\"lostUpdates\":0,\"lostBulkUpdateEvents\":0}",Instant.parse("2026-09-30T12:00:00Z")));
        Script script=new Script(); script.badSync=true; run(store,script);
        check(store.state.equals("STOPPED") && "STATE_SYNC_NOT_READY".equals(store.outcome));
        check(script.downCount==0 && script.upCount==0);
        check(store.checks.stream().anyMatch(s -> s.equals("pre:9:FAIL")));
    }
    @Test void policyMismatchStopsBeforeWrite() {
        Store store=new Store(); Script script=new Script(); script.badPolicy=true; run(store,script);
        check(store.state.equals("STOPPED") && "POLICY_NOT_MATCHED".equals(store.outcome));
        check(script.downCount==0 && script.upCount==0);
        check(store.checks.stream().anyMatch(s -> s.equals("pre:10:FAIL")));
    }
    @Test void postcheckFailStopsWithoutUp() {
        Store store=new Store(); Script script=new Script(); script.badPost=true; run(store,script);
        check(store.state.equals("STOPPED") && script.downCount==1 && script.upCount==0);
        check(store.checks.stream().anyMatch(s -> s.equals("post:8:UNKNOWN")));
    }
    @Test void changedPolicyAfterFailoverStopsWithoutUp() {
        Store store=new Store(); Script script=new Script(); script.changedPolicyPost=true; run(store,script);
        check(store.state.equals("STOPPED") && "POLICY_NOT_MATCHED".equals(store.outcome));
        check(script.downCount==1 && script.upCount==0);
        check(store.checks.stream().anyMatch(s -> s.equals("post:10:FAIL")));
    }
    @Test void expiredWindowNeverContactsDevice() {
        Store store=new Store(); store.valid=false; Script script=new Script(); run(store,script);
        check(store.state.equals("STOPPED") && script.connectCount==0 && script.downCount==0);
    }
    @Test void vsxNeverUsesChassisContext() {
        Store store=new Store(); store.vsId="12"; Script script=new Script(); run(store,script);
        // No VS cpstat gate is approved: refuse before opening either session, never fall back to chassis.
        assertEquals("COMMAND_GATE_UNAVAILABLE",store.outcome);
        assertEquals(0,script.connectCount);
        assertTrue(script.commands.isEmpty());
    }
    @Test void approvedBlockingChecksStopBeforeWrite() {
        for(String command:List.of("cphaprob -ia list","cphaprob show_bond","cpstat os -f routing")) {
            Store store=new Store(); store.kind="READINESS"; Script script=new Script();
            script.unknownCommand=command; run(store,script);
            check("UNKNOWN".equals(store.outcome) && script.downCount==0);
        }
        Store pnotes=new Store(); Script script=new Script(); script.badPnotes=true; run(pnotes,script);
        check("PNOTES_NOT_READY".equals(pnotes.outcome) && script.downCount==0);
        check(pnotes.derivedValues.stream().anyMatch(d -> d.contains("Interface Active Check")));
        Store bonds=new Store(); script=new Script(); script.badBonds=true; run(bonds,script);
        check("BOND_NOT_READY".equals(bonds.outcome) && script.downCount==0);
    }
    @Test void failoverWarningsAndUnknownAreNonBlockingAndDerivedIsSafe() {
        for(boolean unknown:List.of(false,true)) {
            Store store=new Store(); Script script=new Script(); script.recentFailover=true;
            if(unknown) script.unknownCommand="cphaprob show_failover";
            run(store,script);
            check("SUCCEEDED".equals(store.outcome));
            check(store.checks.contains("pre:13:"+(unknown?"UNKNOWN":"WARN")));
            check(store.checks.contains("post:14:PASS"));
            check(store.derivedValues.stream().noneMatch(d -> d.contains("192.0.2.") || d.contains("198.51.100.") || d.contains("eth2-01")));
        }
    }
    @Test void unavailableInformationalFailoverCommandDoesNotBlock() {
        Store store=new Store(); Script script=new Script(); script.failedCommand="cphaprob show_failover";
        run(store,script);
        check("SUCCEEDED".equals(store.outcome) && store.checks.contains("pre:13:UNKNOWN"));
    }
    @Test void routeLossOrMissingDefaultStopsAfterSwitchWithoutReturn() {
        for(boolean missing:List.of(false,true)) {
            Store store=new Store(); Script script=new Script();
            script.missingDefault=missing; script.badRoutes=!missing; run(store,script);
            check("ROUTING_NOT_READY".equals(store.outcome));
            check(script.downCount==1 && script.upCount==0 && store.checks.contains("post:14:FAIL"));
        }
    }
    public static void main(String[] args) {
        var t=new CpFailoverJobExecutorTest(); t.happyPath(); t.precheckFailStopsBeforeWrite();
        t.syncFailureStopsBeforeWrite(); t.policyMismatchStopsBeforeWrite();
        t.failoverTimeoutStopsWithoutUp(); t.postcheckFailStopsWithoutUp(); t.changedPolicyAfterFailoverStopsWithoutUp();
        t.expiredWindowNeverContactsDevice();
        t.vsxNeverUsesChassisContext();
    }
}
