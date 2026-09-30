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
        boolean extraTableRows;
        int downCount,upCount,connectCount;
        final List<String> commands=new ArrayList<>();
        long bytesA=1000,bytesB=1000;
        DeviceTransport transport() {
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
                    if(cmd.endsWith("arp -an")) return new ExecResult.Completed(badPost&&down&&!first?"":
                        "? (192.0.2.31) at 02:00:00:00:00:01 [ether] on eth0\n",0);
                    if(cmd.endsWith("fw tab -t connections -s")) return new ExecResult.Completed(
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
    }
    @Test void differingVsTablesStayUnknownAndStopWithoutWrites() {
        for(boolean extra:List.of(false,true)) {
            Store store=new Store(); store.kind="READINESS"; store.vsId="12";
            Script script=new Script(); script.badPre=!extra; script.extraTableRows=extra;
            run(store,script);
            check("UNKNOWN".equals(store.outcome));
            check(store.checks.contains("pre:2:UNKNOWN"));
            check(store.derivedValues.stream().filter(d -> d.equals(
                "{\"reason\":\"tables differ\",\"a\":"+(extra?4:2)+",\"b\":2}")).count()==2);
            check(script.downCount==0 && script.upCount==0);
        }
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
            "arp -an",5,"fw tab -t connections -s",6,"cat /proc/net/dev",8,"cphaprob syncstat",9,"fw stat",10);
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
        Store store=new Store(); Script script=new Script(); script.badSync=true; run(store,script);
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
        check(store.checks.stream().anyMatch(s -> s.equals("post:5:FAIL")));
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
