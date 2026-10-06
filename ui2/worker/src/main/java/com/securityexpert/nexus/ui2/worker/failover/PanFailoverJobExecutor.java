package com.securityexpert.nexus.ui2.worker.failover;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/** Direct, identity-checked pair execution. A failed step never triggers automatic fail-back. */
public final class PanFailoverJobExecutor {
    private String requestCluster, requestVs;
    private java.util.Set<String> requestMembers = java.util.Set.of();
    private final FailoverMutationSwitch mutationSwitch;
    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }
    private static final String STATE="<show><high-availability><state/></high-availability></show>";
    private static final String SESSION_SYNC="<show><high-availability><state-synchronization/></high-availability></show>";
    private static final String SESSIONS="<show><session><info/></session></show>";
    private static final String SYSTEM="<show><system><info/></system></show>";
    private static final String SUSPEND="<request><high-availability><state><suspend/></state></high-availability></request>";
    private static final String FUNCTIONAL="<request><high-availability><state><functional/></state></high-availability></request>";
    private static final Pattern SUCCESS=Pattern.compile("status\\s*=\\s*[\"']success[\"']");
    private static final Pattern KEY=Pattern.compile("<key>\\s*([^<\\s]+)\\s*</key>");
    private static final Duration POLL=Duration.ofSeconds(3);
    private static final int MAX_POLLS=21;
    private static final String READINESS_KIND="READINESS";
    private record Peer(String id,ApiTarget target,String key,String identity) {}
    private static final class Stop extends RuntimeException {
        final String code; final int check; final String status;
        Stop(String code,int check) { this(code,check,"FAIL"); }
        Stop(String code,int check,String status) { super(code); this.code=code; this.check=check; this.status=status; }
    }
    private final JooqCpFailoverRepository store;
    private final DeviceRepository devices;
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceTransport transport;
    private final PanCredentialResolver credentials;
    private final GateRegistryPort gates;
    private final Pause pause;
    private final Duration commandPause;
    private String jobId,runId;
    private long epoch;
    private int commandIndex;
    private boolean wrote;
    private JooqCpFailoverRepository.Dispatch dispatch;
    private boolean readiness;
    private Stop readinessFailure;
    private String switchMirroredRoles;
    private ReadinessShapeLog shapes;
    private final Map<Integer,Boolean> fieldsFound=new java.util.HashMap<>();

    public PanFailoverJobExecutor(JooqCpFailoverRepository store,DeviceRepository devices,
            JobLeaseRepository leases,JobStepAttemptRepository attempts,DeviceTransport transport,
            PanCredentialResolver credentials,GateRegistryPort gates) {
        this(store,devices,leases,attempts,transport,credentials,gates,d -> Thread.sleep(d.toMillis()),Duration.ofSeconds(2));
    }
    public PanFailoverJobExecutor(JooqCpFailoverRepository store,DeviceRepository devices,
            JobLeaseRepository leases,JobStepAttemptRepository attempts,DeviceTransport transport,
            PanCredentialResolver credentials,GateRegistryPort gates,Pause pause) {
        this(store,devices,leases,attempts,transport,credentials,gates,pause,Duration.ofSeconds(2));
    }
    public PanFailoverJobExecutor(JooqCpFailoverRepository store,DeviceRepository devices,
            JobLeaseRepository leases,JobStepAttemptRepository attempts,DeviceTransport transport,
            PanCredentialResolver credentials,GateRegistryPort gates,Pause pause,Duration commandPause) {
        this(store,devices,leases,attempts,transport,credentials,gates,pause,commandPause,
            FailoverMutationSwitch.fromEnvironment());
    }
    public PanFailoverJobExecutor(JooqCpFailoverRepository store,DeviceRepository devices,
            JobLeaseRepository leases,JobStepAttemptRepository attempts,DeviceTransport transport,
            PanCredentialResolver credentials,GateRegistryPort gates,Pause pause,Duration commandPause,
            FailoverMutationSwitch mutationSwitch) {
        this.mutationSwitch=java.util.Objects.requireNonNull(mutationSwitch);
        this.store=store; this.devices=devices; this.leases=leases; this.attempts=attempts;
        this.transport=transport; this.credentials=credentials; this.gates=gates; this.pause=pause;
        this.commandPause=commandPause;
    }
    public void execute(String jobId,long epoch) {
        var run=store.runByJob(jobId);
        if (run.isEmpty() || !"palo_alto".equals(run.get().vendor()) || run.get().vsId()!=null) {
            leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.FAILED,
                "system:pan-failover-worker","pan_failover_missing_run","RUN_NOT_FOUND");
            return;
        }
        this.jobId=jobId; this.runId=run.get().id(); this.epoch=epoch;
        this.readiness=READINESS_KIND.equals(run.get().kind());
        this.readinessFailure=null;
        this.switchMirroredRoles=null;
        this.shapes=new ReadinessShapeLog("palo_alto");
        this.fieldsFound.clear();
        this.commandIndex=0; this.wrote=false; this.dispatch=null;
        if (!leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.EXECUTING,
                "system:pan-failover-worker","pan_failover_start")) return;
        try {
            if (!readiness && !mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED,0);
            if (!readiness && !store.windowValid(runId)) throw new Stop("WINDOW_EXPIRED",0);
            List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(run.get().clusterRef());
            if (members.size()!=2 || members.get(0).deviceId().equals(members.get(1).deviceId())
                    || members.stream().anyMatch(m -> !"palo_alto".equals(m.vendorHint())))
                throw new Stop("CLUSTER_NOT_ELIGIBLE",0);
            if (!readiness) {
                requestCluster=run.get().clusterRef(); requestVs=run.get().vsId();
                requestMembers=members.stream().map(DeviceSummaryRecord::deviceId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
                requireMutationAdmission(false);
            }
            if (!attempts.findByJobAndStep(jobId,0).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
            for (String command:List.of(STATE,SESSION_SYNC,SESSIONS,SYSTEM)) gate(command);
            if (!readiness) { gate(SUSPEND); gate(FUNCTIONAL); }
            Peer first=peer(members.get(0)),second=peer(members.get(1));
            state("PRECHECK","PRECHECK",null,null,null);
            PanFailoverChecks.State a=read(first),b=read(second);
            Long before=checks("pre",first,second,a,b,null,null);
            if (readiness) {
                finishReadiness("READY",0,"PASS");
                return;
            }
            Peer formerActive="active".equals(a.role())?first:second;
            Peer newActive=formerActive==first?second:first;
            state("FAILING_OVER","FAILING_OVER",null,null,null);
            call(formerActive,SUSPEND);
            if (!waitFor(first,second,newActive,"active",formerActive,"suspended"))
                throw new Stop("FAILOVER_TIMEOUT",1);
            confirmDispatch();
            state("SWITCHED","SWITCHED",null,null,null);
            state("POSTCHECK","POSTCHECK",null,null,null);
            a=read(first); b=read(second);
            checks("post",first,second,a,b,formerActive,before);
            state("RETURNING","RETURNING",null,null,null);
            call(formerActive,FUNCTIONAL);
            if (!waitFor(first,second,newActive,"active",formerActive,"passive"))
                throw new Stop("RETURN_TIMEOUT",1);
            confirmDispatch();
            state("POSTCHECK","POST_RETURN",null,null,null);
            a=read(first); b=read(second);
            checks("post_return",first,second,a,b,formerActive,before);
            if(!"PASS".equals(PanFailoverChecks.roles(read(first),read(second),
                    formerActive==first?"passive":"active",formerActive==second?"passive":"active")))
                throw new Stop("UNEXPECTED_ROLES",1);
            state("DONE","DONE","SUCCEEDED",null,"NO_PROBLEMS_FOUND");
        } catch (Stop stopped) {
            if (readiness) finishReadiness(stopped.code,stopped.check,stopped.check==0?"UNKNOWN":stopped.status); else stop(stopped.code,stopped.check);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (readiness) finishReadiness("INTERRUPTED",0,"UNKNOWN"); else stop("INTERRUPTED",0);
        } catch (RuntimeException unexpected) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unexpected);
            if (readiness) finishReadiness("COLLECTION_FAILED",0,"UNKNOWN");
            else stop(wrote?"OUTCOME_UNCERTAIN":"PRECHECK_UNAVAILABLE",0);
        }
    }
    private void requireMutationAdmission(boolean possibleSend) {
        if (!mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED,0);
        String decision=store.mutationAdmission(runId,requestCluster,requestVs,"palo_alto",requestMembers,possibleSend,jobId,epoch);
        if (!"ADMITTED".equals(decision)) throw new Stop(decision==null?"ADMISSION_UNAVAILABLE":decision,0);
    }

    private void state(String state,String step,String outcome,String check,String message) {
        if (readiness) store.state(runId,state,step,outcome,check,message);
        else if (!store.workerState(runId,epoch,state,step,outcome,check,message))
            throw new Stop("DISPATCH_OWNER_LOST",0);
    }
    private void stop(String code,int check) {
        store.workerState(runId,epoch,"STOPPED","STOPPED",code,check==0?null:String.valueOf(check),code);
    }
    private void confirmDispatch() {
        if (dispatch==null || !store.confirmDispatch(dispatch)) throw new Stop("OUTCOME_UNCERTAIN",0);
    }
    private void finishReadiness(String code,int check,String status) {
        if (readinessFailure!=null && (!"FAIL".equals(status) || "FAIL".equals(readinessFailure.status))) {
            code=readinessFailure.code; check=readinessFailure.check; status=readinessFailure.status;
        }
        String outcome="PASS".equals(status)?"READY":"FAIL".equals(status)?"NOT_READY":"UNKNOWN";
        state("DONE","DONE",outcome,check==0?null:checkName(check),code);
        leases.transitionState(jobId,epoch,JobState.EXECUTING,JobState.COMPLETED,
            "system:pan-failover-worker","pan_readiness_done",outcome);
    }
    private static String checkName(int check) {
        return switch (check) {
            case 1 -> "Mode and roles"; case 2 -> "Peer relationship"; case 3 -> "HA links";
            case 4 -> "Configuration sync"; case 5 -> "Session synchronization";
            case 6 -> "Sessions carried"; case 7 -> "Version parity"; default -> "Pre-check";
        };
    }
    private Peer peer(DeviceSummaryRecord summary) {
        var device=devices.find(summary.deviceId()).filter(d -> d.permitsReadCollection())
            .orElseThrow(() -> new Stop("DEVICE_NOT_ELIGIBLE",0));
        var endpoint=devices.findEndpointByDeviceId(summary.deviceId())
            .filter(e -> summary.deviceId().equals(e.deviceId()) && "pan_xml_api".equalsIgnoreCase(e.transportKind()))
            .orElseThrow(() -> new Stop("ENDPOINT_MISSING",0));
        String identity=devices.findConfirmFacts(summary.deviceId())
            .flatMap(f -> f.recordedIdentityPrimary()).filter(value -> !value.isBlank())
            .orElseThrow(() -> new Stop("IDENTITY_NOT_VERIFIED",0));
        var credential=credentials.resolve(device.credentialReferenceId());
        var target=new ApiTarget(endpoint.endpointId(),endpoint.addressRef());
        var result=transport.xmlApiCall(target,new XmlApiSpec("POST","keygen","","",
            Map.of("user",credential.username(),"password",new String(credential.password())),Map.of()),Duration.ofSeconds(30));
        String body=result instanceof XmlApiResult.Completed c && c.httpStatus()==200?c.body():"";
        var match=KEY.matcher(body);
        if (!SUCCESS.matcher(body).find() || !match.find()) throw new Stop("AUTH_UNAVAILABLE",0);
        return new Peer(summary.deviceId(),target,match.group(1),identity);
    }
    private GateResolution.Known gate(String command) {
        if (!isRead(command) && !mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED,0);
        if (readiness && !writeAllowed(READINESS_KIND,command)) throw new Stop("READINESS_WRITE_REFUSED",0);
        String expected=STATE.equals(command)?"pan_inventory_show_ha_state"
            :SESSION_SYNC.equals(command)?"pan_failover_session_sync"
            :SESSIONS.equals(command)?"pan_failover_session_info"
            :SYSTEM.equals(command)?"pan_inventory_show_system_info"
            :SUSPEND.equals(command)?"pan_failover_suspend"
            :FUNCTIONAL.equals(command)?"pan_failover_functional":null;
        if (expected==null) throw new Stop("COMMAND_NOT_APPROVED",0);
        var resolved=GateResolver.resolve(new CanonicalCommandKey("palo_alto","pan_firewall",
            "not_applicable","PAN_XML_API",command),java.util.Optional.of(isRead(command)
                ?ActionClass.CLASS_0_READ:ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE),gates);
        if (!(resolved instanceof GateResolution.Known known) || !expected.equals(known.gateId()))
            throw new Stop("COMMAND_GATE_UNAVAILABLE",0);
        return known;
    }
    static boolean writeAllowed(String kind,String command) {
        return !READINESS_KIND.equals(kind) || !(SUSPEND.equals(command) || FUNCTIONAL.equals(command));
    }
    private static boolean isRead(String command) {
        return STATE.equals(command) || SESSION_SYNC.equals(command) || SESSIONS.equals(command) || SYSTEM.equals(command);
    }
    private String call(Peer peer,String command) throws InterruptedException {
        var g=gate(command);
        if (commandIndex > 0 && !commandPause.isZero()) pause.sleep(commandPause);
        if (!isRead(command)) {
            if (!mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED,0);
            requireMutationAdmission(false);
            dispatch=store.prepareDispatch(runId,epoch,commandIndex++,peer.id(),g.gateId(),g.actionClass().id());
            wrote=true;
            boolean replied=store.dispatch(dispatch,() -> {
                if (!mutationSwitch.enabled()) return false;
                var result=transport.xmlApiCall(peer.target(),new XmlApiSpec("POST","op","","direct_firewall",
                    Map.of("cmd",command),Map.of("X-PAN-KEY",peer.key())),Duration.ofSeconds(g.timeoutS()));
                return result instanceof XmlApiResult.Completed c && c.httpStatus()==200
                    && c.body()!=null && c.body().length()<=262144 && SUCCESS.matcher(c.body()).find();
            });
            if (!replied) throw new Stop("OUTCOME_UNCERTAIN",0);
            return "";
        }
        if (!attempts.findByJobAndStep(jobId,commandIndex).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
        String attempt=attempts.insertPreContact(jobId,epoch,commandIndex++,g.gateId(),g.actionClass().id(),1);
        if (attempt==null) throw new Stop("PRE_CONTACT_RECORD_FAILED",0);
        if (!attempts.markBoundaryCrossed(attempt,epoch)) throw new Stop("PRE_CONTACT_UNCERTAIN",0);
        store.command(runId,g.gateId());
        var result=transport.xmlApiCall(peer.target(),new XmlApiSpec("POST","op","","direct_firewall",
            Map.of("cmd",command),Map.of("X-PAN-KEY",peer.key())),Duration.ofSeconds(g.timeoutS()));
        if (!(result instanceof XmlApiResult.Completed completed) || completed.httpStatus()!=200
                || completed.body()==null || completed.body().length()>262144
                || !SUCCESS.matcher(completed.body()).find()) {
            attempts.writeOutcome(attempt,epoch,"FAILED","COMMAND_UNAVAILABLE",false,null,null,null);
            throw new Stop("COMMAND_UNAVAILABLE",0);
        }
        if (!attempts.writeOutcome(attempt,epoch,"MATCHED",null,true,null,null,null))
            throw new Stop("ATTEMPT_RECORD_FAILED",0);
        int fieldCheck=STATE.equals(command)?4:SESSION_SYNC.equals(command)?5:SESSIONS.equals(command)?6:0;
        if (fieldCheck!=0) fieldsFound.merge(fieldCheck,PanFailoverChecks.fieldFound(fieldCheck,completed.body()),
            (a,b) -> a && b);
        if (STATE.equals(command)) {
            for (int check=1;check<=4;check++) shapes.capture(check,completed.body());
        } else {
            int check=SESSION_SYNC.equals(command)?5:SESSIONS.equals(command)?6:SYSTEM.equals(command)?7:0;
            if (check!=0) shapes.capture(check,completed.body());
        }
        return completed.body();
    }
    private PanFailoverChecks.State read(Peer peer) throws InterruptedException {
        var state=PanFailoverChecks.parse(call(peer,STATE));
        if (!peer.identity().equals(state.localSerial())) throw new Stop("IDENTITY_NOT_VERIFIED",0);
        return state;
    }
    private Long checks(String phase,Peer first,Peer second,PanFailoverChecks.State a,
            PanFailoverChecks.State b,Peer oldActive,Long before) throws InterruptedException {
        boolean switched="post".equals(phase);
        String returnedRole=switched?"suspended":"passive";
        String role=oldActive==null
            ?("active".equals(a.role())?PanFailoverChecks.roles(a,b,"active","passive")
                :PanFailoverChecks.roles(a,b,"passive","active"))
            :PanFailoverChecks.roles(a,b,oldActive==first?returnedRole:"active",
                oldActive==second?returnedRole:"active",switched);
        String syncA=call(first,SESSION_SYNC);
        String syncB=call(second,SESSION_SYNC);
        Long sessionsA=PanFailoverChecks.sessions(call(first,SESSIONS));
        Long sessionsB=PanFailoverChecks.sessions(call(second,SESSIONS));
        String versions=PanFailoverChecks.versions(call(first,SYSTEM),call(second,SYSTEM));
        Long active=oldActive==null?("active".equals(a.role())?sessionsA:sessionsB)
            :(oldActive==first?sessionsB:sessionsA);
        String carried=sessionsA==null || sessionsB==null?"UNKNOWN"
            :oldActive==null?"PASS":PanFailoverChecks.carried(before,active);
        var sessionSync="active".equals(a.role())?PanFailoverChecks.sessionSyncEvidence(syncA,syncB)
            :"active".equals(b.role())?PanFailoverChecks.sessionSyncEvidence(syncB,syncA)
                :new PanFailoverChecks.SessionSync("UNKNOWN","{\"missing\":[\"active role\"]}");
        var links=PanFailoverChecks.linksEvidence(a,b);
        var configurationSync=PanFailoverChecks.syncEvidence(a,b);
        String[] statuses={role,PanFailoverChecks.relationship(a,b,switched),links.status(),
            configurationSync.status(),sessionSync.status(),carried,versions};
        for (int i=0;i<statuses.length;i++) {
            int no=i+1;
            shapes.logUnknown(no,statuses[i]);
            String reason=PanFailoverChecks.unknownDerived(no,statuses[i],fieldsFound.getOrDefault(no,false));
            if (no==3) reason=links.derived();
            if (no==4) reason=configurationSync.derived();
            if (no==5) reason=sessionSync.derived();
            if (no==6 && !"UNKNOWN".equals(statuses[i])) reason="{\"count\":"+sessionsA+"}";
            String derived=no==1?PanFailoverChecks.stateEvidence(a,b,switchMirroredRoles):reason;
            store.check(runId,phase,first.id(),null,no,statuses[i],derived);
            if (no==6 && !"UNKNOWN".equals(statuses[i])) reason="{\"count\":"+sessionsB+"}";
            derived=no==1?PanFailoverChecks.stateEvidence(b,a,switchMirroredRoles):reason;
            store.check(runId,phase,second.id(),null,no,statuses[i],derived);
        }
        for (int i=0;i<statuses.length;i++) if (!"PASS".equals(statuses[i])) {
            if (!readiness) throw new Stop("CHECK_NOT_READY",i+1,statuses[i]);
            if (readinessFailure==null || !"FAIL".equals(readinessFailure.status) && "FAIL".equals(statuses[i]))
                readinessFailure=new Stop("CHECK_NOT_READY",i+1,statuses[i]);
        }
        return active;
    }
    private boolean waitFor(Peer first,Peer second,Peer active,String activeRole,Peer other,String otherRole)
            throws InterruptedException {
        for (int i=0;i<MAX_POLLS;i++) {
            var a=read(first); var b=read(second);
            String status=PanFailoverChecks.roles(a,b,active==first?activeRole:otherRole,
                active==second?activeRole:otherRole,"suspended".equals(otherRole));
            if("PASS".equals(status)) {
                if("suspended".equals(otherRole)) switchMirroredRoles=PanFailoverChecks.mirroredRoles(a,b);
                return true;
            }
            if("passive".equals(otherRole) && ("active".equals((other==first?a:b).role())
                    || "passive".equals((active==first?a:b).role()))) throw new Stop("UNEXPECTED_ROLES",1);
            if (i<MAX_POLLS-1) pause.sleep(POLL);
        }
        return false;
    }
}
