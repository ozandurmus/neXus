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
        Runnable beforeSend=() -> {};
        boolean notSent;
        String admission="ADMITTED", writeAdmission="ADMITTED", returnAdmission="ADMITTED";
        String failBeforeStep,failAfterStep,lostReplyStep,runtimeErrorStep;
        boolean notSentTransportFailure;
        int prepared,replies,observations;
        boolean suspended,functional,stuck,badPre,badPost,badSync,badSessions,badVersion,missingVersion,lowPostSessions;
        final List<String> sentCommands=new ArrayList<>();
        boolean missingFields,preempt,badFinal;
        /** PO 2026-11-23: zeroSync = every session sync counter 0; noSessions = 0 active sessions on both members. */
        boolean zeroSync,noSessions;
        String evidenceFault;
        String relA="10.2.3",relB="10.2.3";
        /** PO 2026-10-24: the synthetic show session meter answer -- "ok" (two vsys entries), "malformed", "empty". */
        String meter="ok";
        int suspendCount,functionalCount,callCount;
        /** Information check 8 (synthetic): "ok", "reset" (a counter goes down), "malformed", "error" (error envelope). */
        String counters="ok",arp="ok";
        int counterSamples,arpCalls;
        final List<String> counterTargets=new ArrayList<>();
        DeviceTransport transport() {
            return (DeviceTransport) Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[]{DeviceTransport.class},(proxy,method,args) -> {
                    callCount++;
                    if (!"xmlApiCall".equals(method.getName())) throw new AssertionError(method.getName());
                    XmlApiSpec spec=(XmlApiSpec)args[1];
                    if ("keygen".equals(spec.type()))
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result><key>synthetic-key</key></result></response>");
                    String command=spec.formParams().get("cmd");
                    sentCommands.add(command);
                    if (command.contains("<suspend/>")) {
                        suspended=true; suspendCount++;
                        return writeReply("FAILING_OVER");
                    }
                    if (command.contains("<functional/>")) {
                        functional=true; functionalCount++;
                        return writeReply("RETURNING");
                    }
                    boolean first=A.equals(((ApiTarget)args[0]).endpointId());
                    if (command.equals(PanFailoverJobExecutor.COUNTERS)) {
                        counterSamples++; counterTargets.add(((ApiTarget)args[0]).endpointId());
                        if ("error".equals(counters)) return new XmlApiResult.Completed(200,"<response status=\"error\"/>");
                        if ("malformed".equals(counters)) return new XmlApiResult.Completed(200,
                            "<response status=\"success\"><result><hw><entry><name>ethernet1/1</name><ibytes>many</ibytes><obytes>1</obytes></entry></hw></result></response>");
                        long n="reset".equals(counters)?3-counterSamples:counterSamples;
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result>"
                            +"<ifnet><entry><name>ethernet1/1</name><ibytes>7</ibytes><obytes>7</obytes></entry></ifnet><hw>"
                            +"<entry><name>ethernet1/1</name><ibytes>"+(1_000_000+n*10_000)+"</ibytes><obytes>"+(2_000_000+n*10_000)+"</obytes></entry>"
                            +"<entry><name>ethernet1/2</name><ibytes>"+(3_000_000+n*10_000)+"</ibytes><obytes>"+(4_000_000+n*10_000)+"</obytes></entry>"
                            +"<entry><name>mgt</name><ibytes>"+(9_000_000+n*777_000)+"</ibytes><obytes>5</obytes></entry>"
                            +"<entry><name>ha1</name><ibytes>"+(8_000_000+n*777_000)+"</ibytes><obytes>5</obytes></entry>"
                            +"</hw></result></response>");
                    }
                    if (command.equals(PanFailoverJobExecutor.ARP)) {
                        arpCalls++; counterTargets.add("arp:"+((ApiTarget)args[0]).endpointId());
                        if ("error".equals(arp)) return new XmlApiResult.Completed(200,"<response status=\"error\"/>");
                        if ("big".equals(arp)||"huge".equals(arp)) {
                            StringBuilder b=new StringBuilder("<response status=\"success\"><result><entries>");
                            int n=0;
                            while (b.length()<("huge".equals(arp)?8*1024*1024+10:1_000_000)) {
                                b.append("<entry><ip>192.0.2.").append(n%250+1).append("</ip><mac>00:00:5e:00:53:01</mac></entry>"); n++;
                            }
                            return new XmlApiResult.Completed(200,b.append("</entries></result></response>").toString());
                        }
                        if ("zero".equals(arp)) return new XmlApiResult.Completed(200,
                            "<response status=\"success\"><result><total>0</total><entries/></result></response>");
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result><max>8192</max><total>3</total>"
                            +"<entries><entry><ip>192.0.2.1</ip><mac>00:00:5e:00:53:01</mac></entry>"
                            +"<entry><ip>192.0.2.2</ip><mac>00:00:5e:00:53:02</mac></entry>"
                            +"<entry><ip>192.0.2.3</ip><mac>00:00:5e:00:53:03</mac></entry></entries></result></response>");
                    }
                    if (missingFields && !first && (command.contains("<state-synchronization/>")
                            || command.contains("<session><info/>")))
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result><other/>"
                            +"</result></response>");
                    if (command.contains("<state-synchronization/>")) {
                        boolean active=first?!suspended:suspended;
                        return new XmlApiResult.Completed(200,PanFailoverChecksTest.syncXml(
                            badSync && active?"no":active?"yes":"no (device not in active state)",
                            active&&!zeroSync?"20":"0",active||zeroSync?"0":"20"));
                    }
                    if (command.contains("<session><info/>"))
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result><num-active>"
                            +(badSessions&&!first?"invalid":noSessions?"0":lowPostSessions&&suspended&&!first?"79":"100")
                            +"</num-active></result></response>");
                    if (command.contains("<session><meter/>")) {
                        if ("malformed".equals(meter)) return new XmlApiResult.Completed(200,"<response status=\"success\">"
                            +"<result><entry><vsys>1</vsys><current>many</current></entry></result></response>");
                        if ("empty".equals(meter))
                            return new XmlApiResult.Completed(200,"<response status=\"success\"><result/></response>");
                        boolean low=lowPostSessions&&suspended&&!first;
                        return new XmlApiResult.Completed(200,"<response status=\"success\"><result>"
                            +"<entry><vsys>1</vsys><maximum>0</maximum><current>"+(low?"50":"60")+"</current><throttled>0</throttled></entry>"
                            +"<entry><vsys>2</vsys><maximum>0</maximum><current>"+(low?"29":"40")+"</current><throttled>0</throttled></entry>"
                            +"</result></response>");
                    }
                    if (command.contains("<system><info/>"))
                        throw new AssertionError("PO 2026-10-21: show system info is never sent to a firewall; check 7 reads the HA state");
                    String local=first?(functional?"passive":suspended&&!stuck?"suspended":"active")
                        :(suspended&&!stuck?"active":"passive");
                    String peer=first?(suspended&&!stuck?"active":"passive")
                        :(functional?"passive":suspended&&!stuck?"suspended":"active");
                    if(preempt && functional) { local=first?"active":"passive"; peer=first?"passive":"active"; }
                    String ha2=(badFinal && functional || badPre&&!suspended || badPost&&suspended)&&!first?"down":"up";
                    String body="<response status=\"success\"><result><group><mode>Active-Passive</mode>"
                        +"<running-sync>synchronized</running-sync><local-info><state>"+local+"</state>"
                        +(missingVersion&&!first?"":"<build-rel>"+(first?relA:relB)+"</build-rel>")+"<app-version>1111-2222</app-version>"
                        +"<threat-version>"+(badVersion&&!first?"3333-4444":"1111-2222")+"</threat-version>"
                        +"<serial-num>"+(first?"0011":"0022")+"</serial-num></local-info>"
                        +"<peer-info><state>"+peer+"</state><serial-num>"+(first?"0022":"0011")
                        +"</serial-num><conn-status>up</conn-status><conn-ha1><conn-status>up</conn-status>"
                        +"</conn-ha1><conn-ha2><conn-status>"+ha2+"</conn-status></conn-ha2>"
                        +"</peer-info></group></result></response>";
                    if(evidenceFault!=null) {
                        if(evidenceFault.startsWith("post-missing") && suspended && !functional && !first)
                            body=body.replace("<peer-info><state>"+peer+"</state>","<peer-info>");
                        if(evidenceFault.equals("post-missing-opaque") && suspended && !functional && !first)
                            body=body.replace("<peer-info><serial-num>0011", "<peer-info><serial-num>11");
                        if(evidenceFault.equals("return-missing") && functional && !first)
                            body=body.replace("<peer-info><state>"+peer+"</state>","<peer-info>");
                        if(evidenceFault.equals("missing")) body=body.replace("<peer-info><state>"+peer+"</state>","<peer-info>");
                        if(evidenceFault.equals("opposed") || evidenceFault.equals("post-opposed") && suspended)
                            body=body.replace("<peer-info><state>"+peer+"</state>","<peer-info><state>passive</state>");
                        if(evidenceFault.equals("observer")) body=body.replace("<serial-num>"+(first?"0011":"0022")+"</serial-num></local-info>",
                            "<serial-num>"+(first?"0022":"0011")+"</serial-num></local-info>");
                        if(evidenceFault.equals("opaque")) body=body.replace("<peer-info><state>"+peer+"</state><serial-num>"+(first?"0022":"0011"),
                            "<peer-info><state>"+peer+"</state><serial-num>"+(first?"22":"11"));
                        if(evidenceFault.equals("mode")) body=body.replace("Active-Passive","Active-Active");
                        if(evidenceFault.equals("both-active")) body=body.replace("<state>passive</state>","<state>active</state>");
                    }
                    return new XmlApiResult.Completed(200,missingFields && !first
                        ?body.replace("<running-sync>synchronized</running-sync>",""):body);
                });
        }
        XmlApiResult writeReply(String step) {
            if (step.equals(runtimeErrorStep)) {
                if (notSentTransportFailure) throw new JooqCpFailoverRepository.NotSent();
                throw new IllegalStateException("SYNTHETIC_TRANSPORT_FAILURE");
            }
            return new XmlApiResult.Completed(step.equals(lostReplyStep)?504:200,"<response status=\"success\"/>");
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
    /** Test state for information check 8: the two new rows forced SIGNED_OFF (the committed rows are DRAFTED), the earlier run, the clock. */
    private static boolean trafficSigned;
    private static Optional<JooqCpFailoverRepository.Check> previousTraffic=Optional.empty();
    private static java.util.function.LongSupplier nanoClock;
    @org.junit.jupiter.api.BeforeEach void resetTrafficState() {
        trafficSigned=false; previousTraffic=Optional.empty();
        var ticks=new java.util.concurrent.atomic.AtomicLong(-5_000_000_000L);
        nanoClock=() -> ticks.addAndGet(5_000_000_000L);
    }
    private static GateRegistryPort gates() {
        var loaded=GateRegistryFixtureLoader.loadFromStream(PanFailoverJobExecutorTest.class
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
        // V249 signed both rows off (PO 2026-11-17); the tests start from the state before it unless trafficSigned.
        var rows=loaded.stream().map(r -> java.util.Set.of(PanFailoverJobExecutor.COUNTERS_GATE,
                PanFailoverJobExecutor.ARP_GATE).contains(r.gateId())
            ? new com.securityexpert.nexus.ui2.capability.GateRow(r.gateId(),r.vendor(),r.platformRoleScope(),r.shellContext(),
                r.transportKind(),r.canonicalCommandKey(),r.actionClass(),trafficSigned ? com.securityexpert.nexus.ui2.capability.SignOffState.SIGNED_OFF
                    : com.securityexpert.nexus.ui2.capability.SignOffState.DRAFTED,
                r.timeoutS(),r.retryRule(),r.maxFrequency(),r.sessionReuseRule(),r.unsupportedBehaviorRef(),r.secretOutputRisk(),
                r.safeTelemetryFields(),r.sourceDocumentPointer()):r).toList();
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }
    private static Result run(Script script) { return run(script,false); }
    private static Result run(Script script,boolean readiness) {
        return run(script,readiness,!readiness);
    }
    private static Result run(Script script,boolean readiness,boolean enabled) {
        return run(script,readiness,new FailoverMutationSwitch(enabled));
    }
    private static Result run(Script script,boolean readiness,FailoverMutationSwitch mutationSwitch) {
        return run(script,readiness,mutationSwitch,com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.ALL_ENABLED);
    }
    private static Result run(Script script,boolean readiness,FailoverMutationSwitch mutationSwitch,
            com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches panSwitches) {
        JooqCpFailoverRepository store=mock(JooqCpFailoverRepository.class);
        AtomicReference<String> state=new AtomicReference<>("PLANNED"),outcome=new AtomicReference<>(),failedCheck=new AtomicReference<>();
        List<String> checks=new ArrayList<>(),derived=new ArrayList<>();
        when(store.runByJob("job-1")).thenAnswer(inv -> Optional.of(new JooqCpFailoverRepository.Run(
            "run-1",CLUSTER,null,readiness?null:"approval-1","actor-1",Instant.now(),"job-1",
            state.get(),state.get(),outcome.get(),null,null,"palo_alto",readiness?"READINESS":"FAILOVER")));
        when(store.windowValid("run-1")).thenReturn(true);
        AtomicInteger admissions=new AtomicInteger();
        when(store.mutationAdmission(eq("run-1"),eq(CLUSTER),isNull(),eq("palo_alto"),
            eq(java.util.Set.of(A,B)),anyBoolean(),eq("job-1"),eq(1L))).thenAnswer(inv ->
                admissions.incrementAndGet()==1 ? script.admission : script.suspended ? script.returnAdmission : script.writeAdmission);
        doAnswer(inv -> {state.set(inv.getArgument(1)); outcome.set(inv.getArgument(3)); failedCheck.set(inv.getArgument(4)); return null;})
            .when(store).state(anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class));
        when(store.workerState(anyString(),anyLong(),anyString(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class)))
            .thenAnswer(inv -> {if ("STOPPED".equals(state.get())) return false; state.set(inv.getArgument(2)); outcome.set(inv.getArgument(4)); failedCheck.set(inv.getArgument(5)); return true;});
        when(store.prepareDispatch(anyString(),anyLong(),anyInt(),anyString(),anyString(),anyString())).thenAnswer(inv -> {
            if (state.get().equals(script.failBeforeStep)) throw new IllegalStateException("SYNTHETIC_DB_FAILURE");
            script.prepared++;
            return new JooqCpFailoverRepository.Dispatch(state.get(),"run-1","job-1",1);
        });
        when(store.dispatch(any(),any())).thenAnswer(inv -> {
            assertEquals(script.replies+1,script.prepared);
            java.util.function.Supplier<Boolean> send=inv.getArgument(1);
            boolean reply;
            script.beforeSend.run();
            try { reply=send.get(); }
            catch (JooqCpFailoverRepository.NotSent cancelled) {
                script.notSent=true; state.set("STOPPED"); outcome.set("MUTATION_DISABLED_BEFORE_SEND");
                throw cancelled;
            } catch (RuntimeException failure) { reply=false; }
            if (state.get().equals(script.failAfterStep)) throw new IllegalStateException("SYNTHETIC_DB_FAILURE");
            script.replies++; return reply;
        });
        when(store.confirmDispatch(any())).thenAnswer(inv -> {script.observations++; return true;});
        doAnswer(inv -> {checks.add(inv.getArgument(1)+":"+inv.getArgument(4)+":"+inv.getArgument(5));
            derived.add(inv.getArgument(6)); return null;})
            .when(store).check(anyString(),anyString(),anyString(),nullable(String.class),anyInt(),anyString(),anyString());
        when(store.previousReadinessTraffic(anyString(),anyInt())).thenAnswer(inv -> previousTraffic);
        var executor=new PanFailoverJobExecutor(store,devices(),leases(),attempts(),script.transport(),
            ref -> new PanCredentialMaterial("synthetic-user","synthetic-password".toCharArray()),gates(),d -> {},
            Duration.ZERO,mutationSwitch,panSwitches);
        executor.useNanoClock(nanoClock);
        executor.execute("job-1",1);
        return new Result(state.get(),outcome.get(),checks,derived,failedCheck.get());
    }
    private record Result(String state,String outcome,List<String> checks,List<String> derived,String failedCheck) {}
    @Test void switchOffAfterClaimStopsWithoutSending() {
        var enabled=new java.util.concurrent.atomic.AtomicBoolean(true);
        var mutationSwitch=mock(FailoverMutationSwitch.class);
        when(mutationSwitch.enabled()).thenAnswer(inv -> enabled.get());
        Script script=new Script(); script.beforeSend=() -> enabled.set(false);
        Result result=run(script,false,mutationSwitch);
        assertEquals("STOPPED",result.state());
        assertEquals("MUTATION_DISABLED_BEFORE_SEND",result.outcome());
        assertTrue(script.notSent);
        assertEquals(0,script.suspendCount);
        assertEquals(0,script.observations);
    }

    @Test void notSentSignalFromStartedTransportRemainsUncertain() {
        Script script=new Script(); script.runtimeErrorStep="FAILING_OVER"; script.notSentTransportFailure=true;
        Result result=run(script);
        assertEquals("STOPPED",result.state());
        assertEquals("OUTCOME_UNCERTAIN",result.outcome());
        assertTrue(!script.notSent && script.suspendCount==1);
    }

    @Test void everyWriteStopsAtPersistenceAndDeliveryFailuresWithoutReplay() {
        for (String step:List.of("FAILING_OVER","RETURNING")) {
            for (String fault:List.of("before","after","lost","runtime")) {
                Script script=new Script();
                switch (fault) {
                    case "before" -> script.failBeforeStep=step;
                    case "after" -> script.failAfterStep=step;
                    case "lost" -> script.lostReplyStep=step;
                    case "runtime" -> script.runtimeErrorStep=step;
                }
                assertEquals("STOPPED",run(script).state());
                assertEquals("FAILING_OVER".equals(step)&&"before".equals(fault)?0:1,script.suspendCount);
                assertEquals("RETURNING".equals(step)&&!"before".equals(fault)?1:0,script.functionalCount);
                assertEquals("RETURNING".equals(step)?1:0,script.observations);
            }
        }
    }
    @Test void disabledSwitchStopsBeforeTransportOrAttempts() {
        Script script=new Script(); Result result=run(script,false,false);
        assertEquals("STOPPED",result.state());
        assertEquals(FailoverMutationSwitch.DISABLED,result.outcome());
        assertEquals(0,script.suspendCount); assertEquals(0,script.functionalCount);
        assertEquals(0,script.callCount);
    }
    @Test void admissionRefusesBeforeAnyTransport() {
        for (String code:List.of("OPEN_INCIDENT","WRONG_UNIT","MEMBER_SET_CHANGED","FLEET_MUTATION_ACTIVE")) {
            Script script=new Script(); script.admission=code;
            Result result=run(script);
            assertEquals(code,result.outcome()); assertEquals(0,script.callCount);
        }
    }
    /** V260: initiator disabled, approver demoted or one person on both sides -- refused before any call or write. */
    @Test void actorAuthorityRefusalsStopBeforeAnyCallOrWrite() {
        for (String code:List.of(com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED,
            com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.APPROVER_NOT_AUTHORIZED,
            com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.APPROVER_IS_INITIATOR,
            com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.DIRECTORY_AUTHORITY_STALE)) {
            Script start=new Script(); start.admission=code;
            assertEquals(code,run(start).outcome()); assertEquals(0,start.callCount);
            Script beforeWrite=new Script(); beforeWrite.writeAdmission=code;
            assertEquals(code,run(beforeWrite).outcome());
            assertEquals(0,beforeWrite.suspendCount); assertEquals(0,beforeWrite.functionalCount);
        }
    }
    @Test void admissionIsRecheckedAfterReadinessBeforeTheWrite() {
        Script script=new Script(); script.writeAdmission="OPEN_INCIDENT";
        Result result=run(script);
        assertEquals("OPEN_INCIDENT",result.outcome());
        assertEquals(0,script.suspendCount); assertEquals(0,script.functionalCount);
        assertTrue(script.callCount>0);
    }
    @Test void expiryRevocationAndOwnershipBlockDownAndReturn() {
        for (String code:List.of("WINDOW_EXPIRED","APPROVAL_REVOKED","OWNERSHIP_LOST","OPEN_INCIDENT")) {
            Script beforeDown=new Script(); beforeDown.writeAdmission=code;
            assertEquals(code,run(beforeDown).outcome());
            assertEquals(0,beforeDown.suspendCount); assertEquals(0,beforeDown.functionalCount);
            Script beforeUp=new Script(); beforeUp.returnAdmission=code;
            assertEquals(code,run(beforeUp).outcome());
            assertEquals(1,beforeUp.suspendCount); assertEquals(0,beforeUp.functionalCount);
        }
    }
    @Test void missingOpposedOrMisboundEvidenceBlocksWrites() {
        for(String fault:List.of("missing","opposed","observer","opaque","mode","both-active")) {
            Script script=new Script(); script.evidenceFault=fault;
            Result result=run(script);
            assertEquals("STOPPED",result.state(),fault);
            assertEquals(0,script.suspendCount,fault); assertEquals(0,script.functionalCount,fault);
            assertTrue(result.derived().stream().noneMatch(value -> value.contains("0011") || value.contains("0022")));
        }
    }
    @Test void opposedPeerAfterSuspendNeverPermitsReturn() {
        Script script=new Script(); script.evidenceFault="post-opposed";
        Result result=run(script);
        assertEquals("STOPPED",result.state());
        assertEquals(1,script.suspendCount); assertEquals(0,script.functionalCount);
    }
    @Test void missingActivePeerRoleAfterSuspendCompletesWithNotEvaluableMirrorEvidence() {
        Script script=new Script(); script.evidenceFault="post-missing";
        Result result=run(script);
        assertEquals("SUCCEEDED",result.outcome());
        assertEquals(1,script.suspendCount); assertEquals(1,script.functionalCount);
        assertTrue(result.checks().contains("post:1:PASS"));
        assertTrue(result.checks().contains("post:2:PASS"));
        assertEquals(2,result.derived().stream().filter(d -> d.contains("\"mirrored_roles\":\"NOT_EVALUABLE\"")
            && d.contains("\"switch_mirrored_roles\":\"NOT_EVALUABLE\"")
            && d.contains("\"reciprocal_identity\":\"PASS\"")).count());
        assertEquals(42,result.checks().size(),"Preserve distinct pre/post/post-return check rows");
    }
    @Test void missingPostPeerRoleNeverRelaxesSerialReciprocity() {
        Script script=new Script(); script.evidenceFault="post-missing-opaque";
        Result result=run(script);
        assertEquals("FAILOVER_TIMEOUT",result.outcome());
        assertEquals(1,script.suspendCount); assertEquals(0,script.functionalCount);
    }
    @Test void missingPeerRoleAfterReturnDoesNotUseTheSuspendedException() {
        Script script=new Script(); script.evidenceFault="return-missing";
        Result result=run(script);
        assertEquals("RETURN_TIMEOUT",result.outcome());
        assertEquals(1,script.suspendCount); assertEquals(1,script.functionalCount);
    }
    @Test void missingPeerRoleIsUnknownInReadinessProjection() {
        Script script=new Script(); script.evidenceFault="missing";
        Result result=run(script,true);
        assertEquals("UNKNOWN",result.outcome());
        assertTrue(result.checks().contains("pre:2:UNKNOWN"));
        assertTrue(result.derived().stream().anyMatch(value -> value.contains("MISSING_OR_UNRECOGNIZED_ROLE")));
    }
    @Test void happyPath() {
        Script script=new Script(); Result result=run(script);
        assertEquals("DONE",result.state());
        assertEquals(1,script.suspendCount); assertEquals(1,script.functionalCount);
        assertTrue(result.checks().contains("post:7:PASS"));
        for(int no=1;no<=7;no++) assertTrue(result.checks().contains("post_return:"+no+":PASS"));
    }
    @Test void finalHealthFailureStopsAfterBothMutations() {
        Script script=new Script(); script.badFinal=true; Result result=run(script);
        assertEquals("STOPPED",result.state());
        assertTrue(result.checks().contains("post_return:3:FAIL"));
        assertEquals(1,script.suspendCount); assertEquals(1,script.functionalCount);
    }
    @Test void unexpectedPreemptionStopsAfterReturn() {
        Script script=new Script(); script.preempt=true; Result result=run(script);
        assertEquals("STOPPED",result.state()); assertEquals("UNEXPECTED_ROLES",result.outcome());
        assertEquals(1,script.suspendCount); assertEquals(1,script.functionalCount);
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
    /** PO_DECISION_RECORD_2026_11_23_PAN_IDLE_SESSION_SYNC.md: an idle pair's zero counters warn and do not block. */
    @Test void idlePairZeroCountersAreAWarningThatKeepsTheUnitReady() {
        Script script=new Script(); script.zeroSync=true; script.noSessions=true;
        Result result=run(script,true);
        assertEquals("DONE",result.state());
        assertEquals("READY",result.outcome());
        assertEquals(2,result.checks().stream().filter("pre:5:WARN"::equals).count());
        assertTrue(result.checks().contains("pre:3:PASS"));
        assertTrue(result.checks().contains("pre:6:PASS"));
        for(int index:new int[]{8,9})
            assertTrue(result.derived().get(index).contains("\"reason\":\""+PanFailoverChecks.IDLE_SESSION_SYNC_REASON+"\""));
    }
    @Test void idlePairWarningDoesNotBlockTheFailoverRun() {
        Script script=new Script(); script.zeroSync=true; script.noSessions=true;
        Result result=run(script);
        assertEquals("DONE",result.state());
        assertEquals(1,script.suspendCount); assertEquals(1,script.functionalCount);
        assertTrue(result.checks().contains("pre:5:WARN"));
    }
    @Test void idlePairWithHa2DownStaysFail() {
        Script script=new Script(); script.zeroSync=true; script.noSessions=true; script.badPre=true;
        Result result=run(script,true);
        assertEquals("NOT_READY",result.outcome());
        assertTrue(result.checks().contains("pre:3:FAIL"));
        assertTrue(result.checks().contains("pre:5:FAIL"));
        assertFalse(result.checks().contains("pre:5:WARN"));
    }
    @Test void zeroCountersWithSessionsCarriedStayFail() {
        Script script=new Script(); script.zeroSync=true;
        Result result=run(script,true);
        assertEquals("NOT_READY",result.outcome());
        assertEquals("Session synchronization",result.failedCheck());
        assertTrue(result.checks().contains("pre:5:FAIL"));
        Script execution=new Script(); execution.zeroSync=true;
        assertEquals("STOPPED",run(execution).state());
        assertEquals(0,execution.suspendCount);
    }
    @Test void idlePairWithActiveSyncDisabledStaysFail() {
        Script script=new Script(); script.zeroSync=true; script.noSessions=true; script.badSync=true;
        Result result=run(script,true);
        assertEquals("NOT_READY",result.outcome());
        assertTrue(result.checks().contains("pre:5:FAIL"));
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
        assertEquals(16,result.checks().size(),"seven checks and the information check 8, both members");
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
    /** PO 2026-10-21: no run sends show system info; check 7 compares the members' versions from the HA state read. */
    @Test void showSystemInfoIsNeverSentAndVersionParityComesFromTheHaState() {
        for (boolean readiness:new boolean[]{true,false}) {
            Script script=new Script(); Result result=run(script,readiness);
            assertTrue(script.sentCommands.stream().noneMatch(c -> c.contains("<system><info/>")));
            assertTrue(script.sentCommands.stream().anyMatch(c -> c.contains("<high-availability><state/>")));
            assertTrue(result.checks().contains("pre:7:PASS"));
            assertTrue(result.derived().stream().filter(d -> d.contains("\"source\":\"ha_state_local_info\"")
                && d.contains("\"software\":\"MATCH\"") && d.contains("\"threat\":\"MATCH\"")).count() >= 2,
                "both members record the MATCH evidence");
            // Evidence never carries a version value.
            assertTrue(result.derived().stream().noneMatch(d -> d.contains("10.2.3") || d.contains("1111-2222")));
        }
    }
    /** A differing content version is FAIL and a missing PAN-OS build is UNKNOWN: both stop an execution before any write. */
    @Test void versionMismatchAndMissingFieldsStopBeforeAnyWrite() {
        Script mismatch=new Script(); mismatch.badVersion=true; Result failed=run(mismatch);
        assertEquals("STOPPED",failed.state()); assertEquals("CHECK_NOT_READY",failed.outcome());
        assertEquals("7",failed.failedCheck()); assertTrue(failed.checks().contains("pre:7:FAIL"));
        assertTrue(failed.derived().stream().anyMatch(d -> d.contains("\"threat\":\"MISMATCH\"")));
        assertEquals(0,mismatch.suspendCount); assertEquals(0,mismatch.prepared);
        Script missing=new Script(); missing.missingVersion=true; Result unknown=run(missing);
        assertEquals("CHECK_NOT_READY",unknown.outcome()); assertEquals("7",unknown.failedCheck());
        assertTrue(unknown.checks().contains("pre:7:UNKNOWN"));
        assertTrue(unknown.derived().stream().anyMatch(d -> d.contains("\"software\":\"MISSING\"")));
        assertEquals(0,missing.suspendCount); assertEquals(0,missing.prepared);
    }
    /** PO 2026-10-21 scope update: show session info switched off -> never sent; check 6 UNKNOWN with the reason. */
    @Test void showSessionInfoSwitchedOffIsNeverSentAndSessionsCarriedIsUnknown() {
        Script script=new Script();
        Result result=run(script,true,new FailoverMutationSwitch(false),
            com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.from(java.util.Map.of(
                com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE,"retired")));
        assertTrue(script.sentCommands.stream().noneMatch(c -> c.contains("<session><info/>")));
        assertTrue(script.sentCommands.stream().anyMatch(c -> c.contains("<state-synchronization/>")));
        assertTrue(result.checks().contains("pre:6:UNKNOWN"));
        // Check 7 is independent of check 6: with the live manifests' setting, readiness ends UNKNOWN at check 6 only.
        assertTrue(result.checks().contains("pre:7:PASS"));
        assertEquals("UNKNOWN",result.outcome());
        assertEquals(2,result.derived().stream().filter(d -> d.contains("show session info retired by PO 2026-10-21")).count());
        assertEquals(0,script.suspendCount);
    }
    private static final String SESSION_VAR=com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.SHOW_SESSION_INFO_VARIABLE;
    private Result belowTwelve(Script script) {
        return run(script,true,new FailoverMutationSwitch(false),
            com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.from(java.util.Map.of(SESSION_VAR,"below_12")));
    }
    private static long sessionReads(Script script) {
        return script.sentCommands.stream().filter(c -> c.contains("<session><info/>")).count();
    }
    /** PO 2026-10-21 ("cihaz panos 12 degilse bu kontrol calissin"): both members below 12 -> check 6 evaluated. */
    @Test void belowTwelveSendsSessionInfoOnBothMembersAndEvaluatesCheckSix() {
        Script script=new Script(); script.relA="11.1.4-h7"; script.relB="11.1.4-h7";
        Result result=belowTwelve(script);
        assertEquals(2,sessionReads(script));
        assertTrue(result.checks().contains("pre:6:PASS"),result.checks().toString());
        assertTrue(result.derived().stream().noneMatch(d -> d.contains("not_collected")));
    }
    @Test void belowTwelveSkipsSessionInfoOnPanOsTwelveAndSaysWhy() {
        Script script=new Script(); script.relA="12.1.2"; script.relB="12.1.2";
        Result result=belowTwelve(script);
        assertEquals(0,sessionReads(script));
        assertTrue(result.checks().contains("pre:6:UNKNOWN"));
        assertEquals(2,result.derived().stream().filter(d -> d.contains("PAN-OS 12 or later (PO 2026-10-21)")).count());
    }
    @Test void belowTwelveSkipsSessionInfoWhenTheVersionIsUnknown() {
        Script script=new Script(); script.relA="not-a-version"; script.relB="not-a-version";
        Result result=belowTwelve(script);
        assertEquals(0,sessionReads(script));
        assertTrue(result.checks().contains("pre:6:UNKNOWN"));
        assertEquals(2,result.derived().stream().filter(d -> d.contains("PAN-OS version unknown")).count());
    }
    /** One member below 12 and one on 12: only the first is read; check 6 stays UNKNOWN. */
    @Test void belowTwelveMixedMembersReadOnlyTheOldOneAndCheckSixStaysUnknown() {
        Script script=new Script(); script.relA="10.2.9"; script.relB="12.1.2";
        Result result=belowTwelve(script);
        assertEquals(1,sessionReads(script));
        assertTrue(result.checks().contains("pre:6:UNKNOWN"));
        assertEquals(1,result.derived().stream().filter(d -> d.contains("\"detail\":\"PAN-OS 12 or later (PO 2026-10-21)\"")).count());
        assertEquals(1,result.derived().stream().filter(d -> d.contains("peer member: PAN-OS 12 or later")).count());
        assertEquals(0,script.suspendCount);
    }
    // ---- PO 2026-10-24 (replace_12): show session meter replaces show session info on PAN-OS 12 or later ----
    private static com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches replace12() {
        return com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.from(java.util.Map.of(SESSION_VAR,"replace_12"));
    }
    private static long meterReads(Script script) {
        return script.sentCommands.stream().filter(c -> c.equals("<show><session><meter/></session></show>")).count();
    }
    @Test void replaceTwelveReadsTheSessionMeterOnPanOsTwelveAndEvaluatesCheckSixOnTheSum() {
        Script script=new Script(); script.relA="12.1.2"; script.relB="12.1.2";
        Result result=run(script,true,new FailoverMutationSwitch(false),replace12());
        assertEquals(0,sessionReads(script));
        assertEquals(2,meterReads(script));
        assertTrue(result.checks().contains("pre:6:PASS"),result.checks().toString());
        assertEquals("READY",result.outcome());
        assertEquals(2,result.derived().stream().filter(d -> d.equals("{\"count\":100,\"source\":\"session meter\"}")).count());
    }
    /** The 80 % carry rule is unchanged on the summed meter counts: 79 of 100 after the switch stops before the return. */
    @Test void replaceTwelveKeepsTheCarryRuleOnTheMeterCounts() {
        Script script=new Script(); script.relA="12.1.2"; script.relB="12.1.2"; script.lowPostSessions=true;
        Result result=run(script,false,new FailoverMutationSwitch(true),replace12());
        assertEquals("STOPPED",result.state());
        assertTrue(result.checks().contains("post:6:FAIL"),result.checks().toString());
        assertEquals(1,script.suspendCount); assertEquals(0,script.functionalCount);
        assertEquals(0,sessionReads(script));
        assertTrue(result.derived().contains("{\"count\":79,\"source\":\"session meter\"}"));
        Script healthy=new Script(); healthy.relA="12.1.2"; healthy.relB="12.1.2";
        Result done=run(healthy,false,new FailoverMutationSwitch(true),replace12());
        assertEquals("DONE",done.state(),done.checks().toString());
        assertTrue(done.checks().contains("post:6:PASS") && done.checks().contains("post_return:6:PASS"));
    }
    /** Below 12 replace_12 behaves exactly as below_12: show session info, the same evidence, no meter. */
    @Test void replaceTwelveLeavesFirewallsBelowTwelveExactlyAsToday() {
        Script replaced=new Script(); replaced.relA="11.1.4-h7"; replaced.relB="11.1.4-h7";
        Result result=run(replaced,true,new FailoverMutationSwitch(false),replace12());
        Script below=new Script(); below.relA="11.1.4-h7"; below.relB="11.1.4-h7";
        Result today=belowTwelve(below);
        assertEquals(0,meterReads(replaced));
        assertEquals(today.checks(),result.checks());
        assertEquals(today.derived(),result.derived());
        assertEquals(below.sentCommands,replaced.sentCommands);
        assertTrue(result.derived().contains("{\"count\":100}"));
    }
    @Test void replaceTwelveNeverComparesCountsFromTwoDifferentReads() {
        Script script=new Script(); script.relA="10.2.9"; script.relB="12.1.2";
        Result result=run(script,true,new FailoverMutationSwitch(false),replace12());
        assertEquals(1,sessionReads(script)); assertEquals(1,meterReads(script));
        assertTrue(result.checks().contains("pre:6:UNKNOWN"));
        assertEquals(2,result.derived().stream().filter(d -> d.contains("\"reason\":\"mixed_sources\"")).count());
        // Members on different PAN-OS majors also fail version parity (check 7), which outranks the UNKNOWN.
        assertTrue(result.checks().contains("pre:7:FAIL"));
        assertEquals("NOT_READY",result.outcome());
    }
    @Test void replaceTwelveSendsNeitherReadWhenTheVersionIsUnknown() {
        Script script=new Script(); script.relA="not-a-version"; script.relB="not-a-version";
        Result result=run(script,true,new FailoverMutationSwitch(false),replace12());
        assertEquals(0,sessionReads(script)); assertEquals(0,meterReads(script));
        assertTrue(result.checks().contains("pre:6:UNKNOWN"));
        assertEquals(2,result.derived().stream().filter(d -> d.contains("PAN-OS version unknown")).count());
    }
    @Test void anUnusableMeterAnswerIsUnknownNeverAGuessedCount() {
        for (String shape:new String[]{"malformed","empty"}) {
            Script script=new Script(); script.relA="12.1.2"; script.relB="12.1.2"; script.meter=shape;
            Result result=run(script,true,new FailoverMutationSwitch(false),replace12());
            assertTrue(result.checks().contains("pre:6:UNKNOWN"),shape);
            assertEquals(2,result.derived().stream().filter(d -> d.contains("\"source\":\"session meter\"")
                && d.contains("show session meter")).count(),shape+" "+result.derived());
            assertEquals("UNKNOWN",result.outcome());
        }
    }
    public static void main(String[] args) {
        var test=new PanFailoverJobExecutorTest(); test.happyPath(); test.precheckFailNeverWrites();
        test.switchTimeoutDoesNotReturn(); test.postcheckFailDoesNotReturn(); test.addedPrecheckFailuresNeverSuspend();
        test.lowSessionCarryStopsBeforeReturn();
        test.readinessNeverWritesAndRejectsWriteCommands();
    }

    // ---- information check 8: traffic and ARP ---------------------------------------------------------------------------

    private static final String TRAFFIC_ROW_PREFIX="pre:8:";
    private static JooqCpFailoverRepository.Check earlierRun(String derived) {
        return new JooqCpFailoverRepository.Check("pre",B,null,8,"PASS",derived,Instant.EPOCH);
    }
    private static String active8(Result r) {
        return r.derived().stream().filter(d -> d.contains("trafficBytesPerSecond")).findFirst().orElse("");
    }

    @Test void draftedTrafficRowsSendNothingAndCheckEightIsUnknownAndNonBlocking() {
        Script script=new Script();
        Result result=run(script,true);
        assertEquals(0,script.counterSamples+script.arpCalls);
        assertEquals("READY",result.outcome(),"an unapproved information check never blocks");
        assertEquals(2,result.checks().stream().filter(c -> c.startsWith(TRAFFIC_ROW_PREFIX)).count());
        assertTrue(result.checks().contains("pre:8:NOT_EVALUATED"),"the standby's row");
        assertTrue(result.checks().contains("pre:8:UNKNOWN"));
        assertTrue(result.derived().stream().anyMatch(d -> d.contains("\"reason\":\"GATE_NOT_SIGNED_OFF\"")));
        assertTrue(script.sentCommands.stream().noneMatch(c -> c.contains("<counter>") || c.contains("<arp>")));
    }
    @Test void signedOffRowsReadCountersTwiceAndArpOnceOnTheActiveFirewallOnly() {
        trafficSigned=true;
        Script script=new Script();
        Result result=run(script,true);
        assertEquals("READY",result.outcome());
        assertEquals(2,script.counterSamples);
        assertEquals(1,script.arpCalls);
        assertEquals(List.of(A,A,"arp:"+A),script.counterTargets,"the active member (first) only");
        assertEquals(1,result.checks().stream().filter(c -> c.equals("pre:8:PASS")).count());
        assertEquals(1,result.checks().stream().filter(c -> c.equals("pre:8:NOT_EVALUATED")).count());
        String evidence=active8(result);
        // Two forwarding interfaces, 2 x 2 x 10000 bytes in 5 s; mgt and ha1 are left out; ARP total 3.
        assertTrue(evidence.contains("\"trafficBytesPerSecond\":8000"),evidence);
        assertTrue(evidence.contains("\"arpEntries\":3"),evidence);
        assertFalse(evidence.contains("previous"));
        assertFalse(evidence.contains("192.0.2") || evidence.contains("00:00:5e") || evidence.contains("ethernet"));
    }
    @Test void anEarlierRunGivesRatiosAndFlagsADropWithoutBlocking() {
        trafficSigned=true;
        previousTraffic=Optional.of(earlierRun("{\"trafficBytesPerSecond\":20000,\"arpEntries\":3}"));
        Result result=run(new Script(),true);
        String evidence=active8(result);
        assertEquals("READY",result.outcome());
        assertTrue(evidence.contains("\"previousTrafficBytesPerSecond\":20000"),evidence);
        assertTrue(evidence.contains("\"previousArpEntries\":3"),evidence);
        assertTrue(evidence.contains("\"trafficRatio\":0.4"),evidence);
        assertTrue(evidence.contains("\"trafficDropped\":true"),evidence);
        assertTrue(evidence.contains("\"arpRatio\":1.0"),evidence);
        assertTrue(evidence.contains("\"arpDropped\":false"),evidence);
        assertTrue(result.checks().contains("pre:8:PASS"),"a drop is flagged, not failed");
    }
    @Test void aCounterResetAndAnErrorEnvelopeLeaveTrafficUnknownButStillCountArp() {
        trafficSigned=true;
        for (String mode:List.of("reset","malformed","error")) {
            Script script=new Script(); script.counters=mode;
            Result result=run(script,true);
            assertEquals("READY",result.outcome(),mode);
            assertTrue(result.checks().contains("pre:8:UNKNOWN"),mode);
            String evidence=result.derived().stream().filter(d -> d.contains("TRAFFIC_UNAVAILABLE")).findFirst().orElse("");
            assertTrue(evidence.contains("\"arpEntries\":3"),mode+" "+evidence);
            assertFalse(evidence.contains("trafficBytesPerSecond"),mode);
            assertEquals(1,script.arpCalls);
        }
    }
    @Test void anEmptyArpTableIsAQuietWindowUnknownNeverBlocking() {
        trafficSigned=true;
        Script script=new Script(); script.arp="zero";
        Result result=run(script,true);
        assertEquals("READY",result.outcome());
        assertTrue(result.derived().stream().anyMatch(d -> d.contains("\"reason\":\"ARP_ZERO\"") && d.contains("\"arpEntries\":0")));
        script=new Script(); script.arp="error";
        result=run(script,true);
        assertEquals("READY",result.outcome());
        assertTrue(result.derived().stream().anyMatch(d -> d.contains("\"reason\":\"ARP_UNAVAILABLE\"")));
    }
    @Test void aLargeArpBodyIsAcceptedAndCountedButOverEightMiBIsRefused() {
        trafficSigned=true;
        Script script=new Script(); script.arp="big";
        Result result=run(script,true);
        assertEquals("READY",result.outcome());
        assertTrue(result.derived().stream().anyMatch(d -> d.contains("\"arpEntries\":") && !d.contains("ARP_UNAVAILABLE")),result.derived().toString());
        script=new Script(); script.arp="huge";
        result=run(script,true);
        assertTrue(result.derived().stream().anyMatch(d -> d.contains("\"reason\":\"ARP_UNAVAILABLE\"")));
    }
    @Test void onlyTheTwoInformationReadsMayExceedTheSmallCap() {
        String big="<response status=\"success\">"+"x".repeat(262144)+"</response>";
        var r=new XmlApiResult.Completed(200,big);
        assertEquals("TOO_LARGE",PanFailoverJobExecutor.readFailure(r,"<show><other/></show>"));
        assertNull(PanFailoverJobExecutor.readFailure(r,PanFailoverJobExecutor.ARP));
        assertNull(PanFailoverJobExecutor.readFailure(r,PanFailoverJobExecutor.COUNTERS));
        assertEquals("HTTP_500",PanFailoverJobExecutor.readFailure(new XmlApiResult.Completed(500,"x"),PanFailoverJobExecutor.ARP));
        assertEquals("EMPTY",PanFailoverJobExecutor.readFailure(new XmlApiResult.Completed(200,""),PanFailoverJobExecutor.ARP));
        assertEquals("NO_SUCCESS",PanFailoverJobExecutor.readFailure(new XmlApiResult.Completed(200,"<r/>"),PanFailoverJobExecutor.ARP));
    }
    @Test void aFailoverRunNeverReadsTrafficAndArp() {
        trafficSigned=true;
        Script script=new Script();
        Result result=run(script,false);
        assertEquals(0,script.counterSamples+script.arpCalls);
        assertTrue(result.checks().stream().noneMatch(c -> c.contains(":8:")));
    }
}
