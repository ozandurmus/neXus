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
        String vsId;
        Store() { super(new TransactionBoundary() {
            public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext,T> ignored) {
                throw new AssertionError("Database access in fake test");
            }
        }); }
        @Override public Optional<Run> runByJob(String id) {
            return Optional.of(new Run("run-1",CLUSTER,vsId,"approval-1","actor-1",Instant.now(),id,state,state,outcome,null,null));
        }
        @Override public boolean windowValid(String id) { return valid; }
        @Override public void state(String id,String next,String step,String result,String failed,String message) {
            state=next; outcome=result;
        }
        @Override public void command(String id,String gateId) {}
        @Override public void check(String id,String phase,String member,String vs,int no,String status,String derived) {
            checks.add(phase+":"+no+":"+status);
        }
    }
    private static final class Script {
        boolean down,up,stuck,badPre,badPost;
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
                        ?literal.substring("bash -lc 'vsenv 12 && ".length(),literal.length()-1):literal;
                    boolean first=member.endsWith("11");
                    if(cmd.endsWith("clusterXL_admin down")) {down=true;downCount++;return new ExecResult.Completed("ok",0);}
                    if(cmd.endsWith("clusterXL_admin up")) {up=true;upCount++;return new ExecResult.Completed("ok",0);}
                    if(cmd.endsWith("cphaprob stat")) {
                        String roleA=up?"Standby":down&&!stuck?"Down":"Active";
                        String roleB=down&&!stuck?"Active":"Standby";
                        String text="Cluster Mode: High Availability (Active Up)\nNumber Unique Address Assigned Load State\n"
                            +"1 "+(first?"(local) ":"")+"192.0.2.11 100% "+roleA+"\n"
                            +"2 "+(!first?"(local) ":"")+"192.0.2.12 0% "+roleB+"\n";
                        return new ExecResult.Completed(text,0);
                    }
                    if(cmd.endsWith("cphaprob tablestat")) {
                        String text="---- Unique IP's Table ----\nMember Interface IP-Address\n"
                            +"0 1 192.0.2.21\n1 1 "+(badPre&&!first&&!down?"192.0.2.99":"192.0.2.22")+"\n";
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
    }
    @Test void precheckFailStopsBeforeWrite() {
        Store store=new Store(); Script script=new Script(); script.badPre=true; run(store,script);
        check(store.state.equals("STOPPED") && script.downCount==0 && script.upCount==0);
        check(store.checks.stream().anyMatch(s -> s.equals("pre:2:FAIL")));
    }
    @Test void failoverTimeoutStopsWithoutUp() {
        Store store=new Store(); Script script=new Script(); script.stuck=true; run(store,script);
        check(store.state.equals("STOPPED") && "FAILOVER_TIMEOUT".equals(store.outcome));
        check(script.downCount==1 && script.upCount==0);
    }
    @Test void postcheckFailStopsWithoutUp() {
        Store store=new Store(); Script script=new Script(); script.badPost=true; run(store,script);
        check(store.state.equals("STOPPED") && script.downCount==1 && script.upCount==0);
        check(store.checks.stream().anyMatch(s -> s.equals("post:5:FAIL")));
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
    public static void main(String[] args) {
        var t=new CpFailoverJobExecutorTest(); t.happyPath(); t.precheckFailStopsBeforeWrite();
        t.failoverTimeoutStopsWithoutUp(); t.postcheckFailStopsWithoutUp(); t.expiredWindowNeverContactsDevice();
        t.vsxNeverUsesChassisContext();
    }
}
