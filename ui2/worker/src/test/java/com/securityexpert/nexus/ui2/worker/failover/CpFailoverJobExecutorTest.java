package com.securityexpert.nexus.ui2.worker.failover;

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
        String state="PLANNED",outcome; boolean valid=true; final List<String> checks=new ArrayList<>();
        final List<String> derivedValues=new ArrayList<>();
        String vsId,kind="FAILOVER";
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
        @Override public boolean windowValid(String id) { return valid; }
        @Override public void state(String id,String next,String step,String result,String failed,String message) {
            state=next; outcome=result;
        }
        @Override public void command(String id,String gateId) {}
        @Override public void check(String id,String phase,String member,String vs,int no,String status,String derived) {
            checks.add(phase+":"+no+":"+status);
            derivedValues.add(derived);
        }
    }
    private static final class Script {
        boolean down,up,stuck,badPre,badPost,badSync,badPolicy,changedPolicyPost;
        boolean readyMember,badPnotes,badBonds,badRoutes,missingDefault,recentFailover;
        String unknownCommand, failedCommand;
        boolean extraTableRows,reverseActive;
        String peerPolicyTime;
        long[] connectionCounts;
        String prefix="";
        Map<String,String> measured=Map.of();
        int downCount,upCount,connectCount;
        final List<String> commands=new ArrayList<>();
        long bytesA=1000,bytesB=1000;
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
                        .replace("Active","TEMP").replace("Standby","Active").replace("TEMP","Standby");
                    if(reverseActive && literal.endsWith("fw tab -t connections -s'")) {
                        boolean first=((TransportSession)args[0]).sessionId().endsWith("11");
                        output=output.replace("8158 100 150",first?"8158 1168 1500":"8158 47 1500");
                    }
                    if(connectionCounts!=null && literal.endsWith("fw tab -t connections -s'")) {
                        boolean first=((TransportSession)args[0]).sessionId().endsWith("11");
                        long count=connectionCounts[first?0:1];
                        output="HOST NAME ID #VALS #PEAK #SLINKS\nlocalhost connections 8158 "+count+" 60000 0\n";
                    }
                    return new ExecResult.Completed(prefix+output,c.exitStatus());
                });
        }
        DeviceTransport baseTransport() {
            return (DeviceTransport)Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[]{DeviceTransport.class},(proxy,method,args) -> {
                    if(method.getName().equals("connect")) {
                        connectCount++;
                        ConnectionTarget target=(ConnectionTarget)args[0];
                        return new ConnectResult.Authenticated(() -> target.host());
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
                    if(cmd.endsWith("clusterXL_admin down")) {down=true;downCount++;return new ExecResult.Completed("ok",0);}
                    if(cmd.endsWith("clusterXL_admin up")) {up=true;upCount++;return new ExecResult.Completed("ok",0);}
                    if(cmd.endsWith("cphaprob stat")) {
                        String roleA=up?"Standby":down&&!stuck?"Down":"Active";
                        String roleB=readyMember?"READY":down&&!stuck?"Active":"Standby";
                        String text="Cluster Mode: High Availability (Active Up)\nNumber Unique Address Assigned Load State\n"
                            +"1 "+(first?"(local) ":"")+"192.0.2.11 100% "+roleA+"\n"
                            +"2 "+(!first?"(local) ":"")+"192.0.2.12 0% "+roleB+"\n";
                        return new ExecResult.Completed(text,0);
                    }
                    if(cmd.endsWith("cphaprob tablestat")) {
                        String text="---- Unique IP's Table ----\nMember Interface IP-Address\n"
                            +"0 1 192.0.2.21\n1 1 "+(badPre&&!first&&!down?"192.0.2.99":"192.0.2.22")+"\n";
                        if(extraTableRows && first) text+="0 2 198.51.100.21\n1 2 198.51.100.22\n";
                        return new ExecResult.Completed(text,0);
                    }
                    if(cmd.endsWith("cphaprob -a if")) return new ExecResult.Completed(
                        "CCP mode: Automatic\nRequired interfaces: 1\neth0 UP non sync\n",0);
                    if(cmd.endsWith("arp -an")) return new ExecResult.Completed(
                        "? (192.0.2.31) at 02:00:00:00:00:01 [ether] on eth0\n",0);
                    if(cmd.endsWith("fw tab -t connections -s")) return new ExecResult.Completed(badPost&&down&&!first?"":
                        "HOST NAME ID #VALS #PEAK #SLINKS\nlocalhost connections 8158 100 150 0\n",0);
                    if(cmd.endsWith("cat /proc/net/dev")) {
                        long bytes=first?(bytesA+=1000):(bytesB+=1000);
                        return new ExecResult.Completed("Inter-| Receive | Transmit\nface |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n"
                            +"eth0: "+bytes+" 0 0 0 0 0 0 0 100 0 0 0 0 0 0 0\n",0);
                    }
                    if(cmd.endsWith("cphaprob syncstat")) return new ExecResult.Completed(
                        "Delta Sync Statistics\nSync status: OK\nDrops:\nLost updates................................. "
                            +(badSync&&!first&&!down?"1":"0")+"\nLost bulk update events...................... 0\nSent reject notifications.................... 4554\nReceived reject notifications................ 0\n",0);
                    if(cmd.endsWith("fw stat")) return new ExecResult.Completed(
                        "HOST POLICY DATE\nlocalhost "+((badPolicy&&!first&&!down || changedPolicyPost&&down)?"Other_Policy":"Sample_Policy")
                            +" 10Sep2018 14:01:25 : [>eth0]\n",0);
                    if(cmd.equals("cphaprob -ia list")) return new ExecResult.Completed(
                        Fixtures.read("cp/failover_pnotes_"+(badPnotes || down&&first?"problem":"ok")+".txt"),0);
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
        return (DeviceRepository)Proxy.newProxyInstance(DeviceRepository.class.getClassLoader(),
            new Class<?>[]{DeviceRepository.class},(proxy,method,args) -> switch(method.getName()) {
                case "findMembersByClusterRef" -> List.of(summary(A),summary(B));
                case "find" -> Optional.of(new DeviceRecord((String)args[0],"gateway","check_point","manual",
                    Instant.EPOCH,false,DeviceEnrollmentState.ENROLLED,false,"credential-ref"));
                case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord("endpoint-ref",(String)args[0],"ssh_exec",
                    A.equals(args[0])?"192.0.2.11":"192.0.2.12",Instant.EPOCH));
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
        new CpFailoverJobExecutor(store,devices(),leases(),attempts(),script.transport(),gates(),d -> {})
            .execute("job-1",1);
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
            org.junit.jupiter.api.Assertions.assertEquals("READY",store.outcome,shape+" banner="+banner+" "+store.checks);
            check(store.checks.size()==24 && script.downCount==0 && script.upCount==0);
        }
        Store store=new Store(); store.kind="READINESS"; store.vsId="12";
        Script script=new Script(); script.reverseActive=true;
        run(store,script);
        check(store.checks.contains("pre:6:PASS"));
        check("READY".equals(store.outcome));
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
    @Test void connectionRuleUsesActualActiveMemberAndPersistsBothCounts() {
        for(boolean reverse:List.of(false,true)) for(boolean high:List.of(false,true)) {
            Store store=new Store(); store.kind="READINESS";
            Script script=new Script(); script.reverseActive=reverse;
            long active=high?10000:3127,standby=high?7999:2285;
            script.connectionCounts=reverse?new long[]{standby,active}:new long[]{active,standby};
            run(store,script);
            check(store.checks.contains(high?"pre:6:FAIL":"pre:6:PASS"));
            check(store.derivedValues.stream().filter(d -> d.contains("\"activeCount\":"+active)
                && d.contains("\"comparedCount\":"+standby) && d.contains("\"ratio\":")
                && d.contains(high?"RATIO_80":"LOW_VOLUME_ABSOLUTE_OR_RATIO")).count()==2);
            check(script.downCount==0 && script.upCount==0);
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
    @Test void precheckFailStopsBeforeWrite() {
        Store store=new Store(); Script script=new Script(); script.badPre=true; run(store,script);
        check(store.state.equals("STOPPED") && script.downCount==0 && script.upCount==0);
        check(store.checks.stream().anyMatch(s -> s.equals("pre:2:FAIL")));
        check(store.checks.size()==4);
        check(script.commands.size()==4);
    }
    @Test void differingVsTablesStayUnknownAndStopWithoutWrites() {
        for(boolean extra:List.of(false,true)) {
            Store store=new Store(); store.kind="READINESS"; store.vsId="12";
            Script script=new Script(); script.badPre=!extra; script.extraTableRows=extra;
            run(store,script);
            check("UNKNOWN".equals(store.outcome));
            check(store.checks.contains("pre:2:UNKNOWN"));
            check(store.derivedValues.stream().filter(d -> d.contains("\"reason\":\"tables differ\"")
                && d.contains("\"firstEntries\":"+(extra?4:2)) && d.contains("\"secondEntries\":2")
                && d.contains(extra?"MISSING_ON_SECOND":"ADDRESS_MISMATCH")).count()==2);
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
            check(script.connectCount==2 && script.commands.size()==26);
            check(script.downCount==0 && script.upCount==0);
        }
    }
    @Test void readinessStopsWhenCommandUnavailable() {
        Store store=new Store(); store.kind="READINESS";
        Script script=new Script(); script.failedCommand="cphaprob tablestat";
        run(store,script);
        check("UNKNOWN".equals(store.outcome) && store.checks.size()==2);
        check(script.commands.size()==3 && script.downCount==0);
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
            "fw tab -t connections -s",6,"cat /proc/net/dev",8,"cphaprob syncstat",9,"fw stat",10);
        commands.forEach((command,no) -> {
            Store store=new Store(); store.kind="READINESS";
            Script script=new Script(); script.unknownCommand=command; run(store,script);
            check("UNKNOWN".equals(store.outcome));
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
        check(store.checks.stream().anyMatch(s -> s.equals("post:6:UNKNOWN")));
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
        check(store.state.equals("DONE") && script.downCount==1 && script.upCount==1);
        check(script.commands.stream().allMatch(c -> c.startsWith("bash -lc 'vsenv 12 && ")));
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
