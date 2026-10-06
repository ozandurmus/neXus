package com.securityexpert.nexus.ui2.worker.failover;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/** Scripted XML API only: no socket, host, or device contact. */
class PanFailoverJobExecutorTest {
    private static final String CLUSTER="CLS-TEST-01", A="FW-TEST-01", B="FW-TEST-02";
    private static final class Script {
        boolean suspended,functional,stuck,badPre,badPost,badSync,badSessions,badVersion,lowPostSessions;
        boolean missingFields;
        int suspendCount,functionalCount,callCount;
        DeviceTransport transport() {
            return (DeviceTransport) Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[]{DeviceTransport.class},(proxy,method,args) -> {
                    callCount++;
                    if (!"xmlApiCall".equals(method.getName())) throw new AssertionError(method.getName());
                    XmlApiSpec spec=(XmlApiSpec)args[1];
                    if ("keygen".equals(spec.type()))
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result><key>synthetic-key</key></result></response>");
                    String command=spec.formParams().get("cmd");
                    if (command.contains("<suspend/>")) {
                        suspended=true; suspendCount++;
                        return new XmlApiResult.Completed(200,"<response status=\"success\"/>");
                    }
                    if (command.contains("<functional/>")) {
                        functional=true; functionalCount++;
                        return new XmlApiResult.Completed(200,"<response status=\"success\"/>");
                    }
                    boolean first=A.equals(((ApiTarget)args[0]).endpointId());
                    if (missingFields && !first && (command.contains("<state-synchronization/>")
                            || command.contains("<session><info/>")))
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result><other/>"
                            +"</result></response>");
                    if (command.contains("<state-synchronization/>")) {
                        boolean active=first?!suspended:suspended;
                        return new XmlApiResult.Completed(200,PanFailoverChecksTest.syncXml(
                            badSync && active?"no":active?"yes":"no (device not in active state)",
                            active?"20":"0",active?"0":"20"));
                    }
                    if (command.contains("<session><info/>"))
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result><num-active>"
                            +(badSessions&&!first?"invalid":lowPostSessions&&suspended&&!first?"79":"100")
                            +"</num-active></result></response>");
                    if (command.contains("<system><info/>"))
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result><system>"
                            +"<sw-version>1</sw-version><app-version>2</app-version><threat-version>"
                            +(badVersion&&!first?"4":"3")+"</threat-version></system></result></response>");
                    String local=first?(functional?"passive":suspended&&!stuck?"suspended":"active")
                        :(suspended&&!stuck?"active":"passive");
                    String peer=first?(suspended&&!stuck?"active":"passive")
                        :(functional?"passive":suspended&&!stuck?"suspended":"active");
                    String ha2=(badPre&&!suspended || badPost&&suspended)&&!first?"down":"up";
                    String body="<response status=\"success\"><result><group><mode>Active-Passive</mode>"
                        +"<running-sync>synchronized</running-sync><local-info><state>"+local+"</state>"
                        +"<serial-num>"+(first?"0011":"0022")+"</serial-num></local-info>"
                        +"<peer-info><state>"+peer+"</state><serial-num>"+(first?"0022":"0011")
                        +"</serial-num><conn-status>up</conn-status><conn-ha1><conn-status>up</conn-status>"
                        +"</conn-ha1><conn-ha2><conn-status>"+ha2+"</conn-status></conn-ha2>"
                        +"</peer-info></group></result></response>";
                    return new XmlApiResult.Completed(200,missingFields && !first
                        ?body.replace("<running-sync>synchronized</running-sync>",""):body);
                });
        }
    }
    private static DeviceSummaryRecord summary(String id) {
        return new DeviceSummaryRecord(id,"gateway","palo_alto",DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(CLUSTER));
    }
    private static DeviceRepository devices() {
        return (DeviceRepository)Proxy.newProxyInstance(DeviceRepository.class.getClassLoader(),
            new Class<?>[]{DeviceRepository.class},(proxy,method,args) -> switch(method.getName()) {
                case "findMembersByClusterRef" -> List.of(summary(A),summary(B));
                case "find" -> Optional.of(new DeviceRecord((String)args[0],"gateway","palo_alto","manual",
                    Instant.EPOCH,false,DeviceEnrollmentState.ENROLLED,false,"credential-ref"));
                case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord((String)args[0],(String)args[0],
                    "pan_xml_api",A.equals(args[0])?"192.0.2.11":"192.0.2.12",Instant.EPOCH));
                case "findConfirmFacts" -> Optional.of(new DeviceConfirmFacts(Optional.empty(),Optional.empty(),
                    Optional.empty(),Optional.empty(),Optional.of(A.equals(args[0])?"0011":"0022"),Optional.empty(),
                    DeviceConfirmFacts.IDENTITY_MISMATCH_NONE,Optional.empty(),Optional.empty(),Optional.of(CLUSTER),
                    Optional.empty(),DeviceConfirmFacts.PEER_FOLLOW_CORROBORATED,Optional.empty()));
                default -> throw new AssertionError(method.getName());
            });
    }
    private static JobLeaseRepository leases() {
        return (JobLeaseRepository)Proxy.newProxyInstance(JobLeaseRepository.class.getClassLoader(),
            new Class<?>[]{JobLeaseRepository.class},(proxy,method,args) -> {
                if ("transitionState".equals(method.getName())) return true;
                throw new AssertionError(method.getName());
            });
    }
    private static JobStepAttemptRepository attempts() {
        AtomicInteger counter=new AtomicInteger();
        return (JobStepAttemptRepository)Proxy.newProxyInstance(JobStepAttemptRepository.class.getClassLoader(),
            new Class<?>[]{JobStepAttemptRepository.class},(proxy,method,args) -> switch(method.getName()) {
                case "findByJobAndStep" -> List.of();
                case "insertPreContact" -> "attempt-"+counter.incrementAndGet();
                case "markBoundaryCrossed","writeOutcome" -> true;
                default -> throw new AssertionError(method.getName());
            });
    }
    private static GateRegistryPort gates() {
        var rows=GateRegistryFixtureLoader.loadFromStream(PanFailoverJobExecutorTest.class
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }
    private static Result run(Script script) { return run(script,false); }
    private static Result run(Script script,boolean readiness) {
        return run(script,readiness,!readiness);
    }
    private static Result run(Script script,boolean readiness,boolean enabled) {
        JooqCpFailoverRepository store=mock(JooqCpFailoverRepository.class);
        AtomicReference<String> state=new AtomicReference<>("PLANNED"),outcome=new AtomicReference<>(),failedCheck=new AtomicReference<>();
        List<String> checks=new ArrayList<>(),derived=new ArrayList<>();
        when(store.runByJob("job-1")).thenAnswer(inv -> Optional.of(new JooqCpFailoverRepository.Run(
            "run-1",CLUSTER,null,readiness?null:"approval-1","actor-1",Instant.now(),"job-1",
            state.get(),state.get(),outcome.get(),null,null,"palo_alto",readiness?"READINESS":"FAILOVER")));
        when(store.windowValid("run-1")).thenReturn(true);
        doAnswer(inv -> {state.set(inv.getArgument(1)); outcome.set(inv.getArgument(3)); failedCheck.set(inv.getArgument(4)); return null;})
            .when(store).state(anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class));
        doAnswer(inv -> {checks.add(inv.getArgument(1)+":"+inv.getArgument(4)+":"+inv.getArgument(5));
            derived.add(inv.getArgument(6)); return null;})
            .when(store).check(anyString(),anyString(),anyString(),nullable(String.class),anyInt(),anyString(),anyString());
        new PanFailoverJobExecutor(store,devices(),leases(),attempts(),script.transport(),
            ref -> new PanCredentialMaterial("synthetic-user","synthetic-password".toCharArray()),gates(),d -> {},
            Duration.ZERO,new FailoverMutationSwitch(enabled))
            .execute("job-1",1);
        return new Result(state.get(),outcome.get(),checks,derived,failedCheck.get());
    }
    private record Result(String state,String outcome,List<String> checks,List<String> derived,String failedCheck) {}
    @Test void disabledSwitchStopsBeforeTransportOrAttempts() {
        Script script=new Script(); Result result=run(script,false,false);
        assertEquals("STOPPED",result.state());
        assertEquals(FailoverMutationSwitch.DISABLED,result.outcome());
        assertEquals(0,script.suspendCount); assertEquals(0,script.functionalCount);
        assertEquals(0,script.callCount);
    }
    @Test void happyPath() {
        Script script=new Script(); Result result=run(script);
        assertEquals("DONE",result.state());
        assertEquals(1,script.suspendCount); assertEquals(1,script.functionalCount);
        assertTrue(result.checks().contains("post:7:PASS"));
    }
    @Test void sessionSyncFailurePersistsProofForBothMemberRows() {
        Script script=new Script(); script.badSync=true;
        Result result=run(script,true);
        assertTrue(result.checks().contains("pre:5:FAIL"));
        for(int index:new int[]{8,9}) {
            String derived=result.derived().get(index);
            assertTrue(derived.contains("\"active\":{\"enabled\":false"));
            assertTrue(derived.contains("\"sent\":20,\"recv\":0"));
            assertTrue(derived.contains("\"passive\":{\"enabled\":false"));
        }
    }
    @Test void precheckFailNeverWrites() {
        Script script=new Script(); script.badPre=true; Result result=run(script);
        assertEquals("STOPPED",result.state());
        assertEquals(0,script.suspendCount); assertEquals(0,script.functionalCount);
        assertTrue(result.checks().contains("pre:3:FAIL"));
    }
    @Test void switchTimeoutDoesNotReturn() {
        Script script=new Script(); script.stuck=true; Result result=run(script);
        assertEquals("FAILOVER_TIMEOUT",result.outcome());
        assertEquals(1,script.suspendCount); assertEquals(0,script.functionalCount);
    }
    @Test void postcheckFailDoesNotReturn() {
        Script script=new Script(); script.badPost=true; Result result=run(script);
        assertEquals("STOPPED",result.state());
        assertEquals(1,script.suspendCount); assertEquals(0,script.functionalCount);
        assertTrue(result.checks().contains("post:3:FAIL"));
    }
    @Test void readinessLinkFailurePersistsBothMemberStates() {
        Script script=new Script(); script.badPre=true;
        Result result=run(script,true);
        assertEquals("NOT_READY",result.outcome());
        assertTrue(result.checks().contains("pre:3:FAIL"));
        for(int index:new int[]{4,5}) {
            assertTrue(result.derived().get(index).contains("\"first\":{\"ha1\":\"up\""));
            assertTrue(result.derived().get(index).contains("\"ha2\":\"down\""));
        }
        assertEquals(0,script.suspendCount);
    }
    @Test void addedPrecheckFailuresNeverSuspend() {
        for (int check=5;check<=7;check++) {
            Script script=new Script();
            script.badSync=check==5; script.badSessions=check==6; script.badVersion=check==7;
            Result result=run(script);
            assertEquals("STOPPED",result.state());
            assertEquals(0,script.suspendCount);
            assertTrue(result.checks().contains("pre:"+check+":"+(check==6?"UNKNOWN":"FAIL")));
        }
    }
    @Test void lowSessionCarryStopsBeforeReturn() {
        Script script=new Script(); script.lowPostSessions=true;
        Result result=run(script);
        assertEquals("STOPPED",result.state());
        assertEquals(1,script.suspendCount);
        assertEquals(0,script.functionalCount);
        assertTrue(result.checks().contains("post:6:FAIL"));
    }
    @Test void readinessNeverWritesAndRejectsWriteCommands() {
        Script script=new Script(); Result result=run(script,true);
        assertEquals("DONE",result.state());
        assertEquals("READY",result.outcome());
        assertEquals(0,script.suspendCount); assertEquals(0,script.functionalCount);
        assertFalse(PanFailoverJobExecutor.writeAllowed("READINESS",
            "<request><high-availability><state><suspend/></state></high-availability></request>"));
        assertFalse(PanFailoverJobExecutor.writeAllowed("READINESS",
            "<request><high-availability><state><functional/></state></high-availability></request>"));
    }
    @Test void missingFieldsOnOnePeerKeepChecksUnknownAndExplainTheLookup() {
        Script script=new Script(); script.missingFields=true;
        Result result=run(script,true);
        assertEquals("UNKNOWN",result.outcome());
        assertEquals(0,script.suspendCount); assertEquals(0,script.functionalCount);
        for (int check=4;check<=6;check++) {
            assertTrue(result.checks().contains("pre:"+check+":UNKNOWN"));
            if(check==5) {
                for(int index:new int[]{8,9}) {
                    assertTrue(result.derived().get(index).contains("\"missing\":[\"passive.enabled\""));
                    assertTrue(result.derived().get(index).contains("\"active\":{\"enabled\":true"));
                }
                continue;
            }
            if(check==4) {
                for(int index:new int[]{6,7}) {
                    assertTrue(result.derived().get(index).contains("second.running-sync"));
                    assertTrue(result.derived().get(index).contains("\"running-sync\":\"synchronized\""));
                }
                continue;
            }
            String expected=PanFailoverChecks.unknownDerived(check,"UNKNOWN",false);
            assertEquals(expected,result.derived().get((check-1)*2));
            assertEquals(expected,result.derived().get((check-1)*2+1));
        }
    }
    @Test void readinessRecordsEveryCheckAndFailureOutranksEarlierUnknown() {
        Script script=new Script(); script.missingFields=true; script.badVersion=true;
        Result result=run(script,true);
        assertEquals("NOT_READY",result.outcome());
        assertEquals(14,result.checks().size());
        assertTrue(result.checks().contains("pre:4:UNKNOWN"));
        assertEquals(2,result.checks().stream().filter(c -> c.equals("pre:7:FAIL")).count());
        assertEquals(0,script.suspendCount);
        // The execution path still stops on its first non-PASS verdict.
        Result failover=run(script,false);
        assertEquals("STOPPED",failover.state());
        assertEquals("CHECK_NOT_READY",failover.outcome());
        assertEquals("4",failover.failedCheck());
        assertEquals(0,script.suspendCount);
    }
    public static void main(String[] args) {
        var test=new PanFailoverJobExecutorTest(); test.happyPath(); test.precheckFailNeverWrites();
        test.switchTimeoutDoesNotReturn(); test.postcheckFailDoesNotReturn(); test.addedPrecheckFailuresNeverSuspend();
        test.lowSessionCarryStopsBeforeReturn();
        test.readinessNeverWritesAndRejectsWriteCommands();
    }
}
