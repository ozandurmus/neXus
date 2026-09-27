package com.securityexpert.nexus.ui2.worker.failover;

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
    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }
    private static final String STATE="<show><high-availability><state/></high-availability></show>";
    private static final String SUSPEND="<request><high-availability><state><suspend/></state></high-availability></request>";
    private static final String FUNCTIONAL="<request><high-availability><state><functional/></state></high-availability></request>";
    private static final Pattern KEY=Pattern.compile("<key>\\s*([^<\\s]+)\\s*</key>");
    private static final Duration POLL=Duration.ofSeconds(3);
    private static final int MAX_POLLS=21;
    private record Peer(String id,ApiTarget target,String key,String identity) {}
    private static final class Stop extends RuntimeException {
        final String code; final int check;
        Stop(String code,int check) { super(code); this.code=code; this.check=check; }
    }
    private final JooqCpFailoverRepository store;
    private final DeviceRepository devices;
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceTransport transport;
    private final PanCredentialResolver credentials;
    private final GateRegistryPort gates;
    private final Pause pause;
    private String jobId,runId;
    private long epoch;
    private int commandIndex;
    private boolean wrote,writeInFlight;

    public PanFailoverJobExecutor(JooqCpFailoverRepository store,DeviceRepository devices,
            JobLeaseRepository leases,JobStepAttemptRepository attempts,DeviceTransport transport,
            PanCredentialResolver credentials,GateRegistryPort gates) {
        this(store,devices,leases,attempts,transport,credentials,gates,d -> Thread.sleep(d.toMillis()));
    }
    public PanFailoverJobExecutor(JooqCpFailoverRepository store,DeviceRepository devices,
            JobLeaseRepository leases,JobStepAttemptRepository attempts,DeviceTransport transport,
            PanCredentialResolver credentials,GateRegistryPort gates,Pause pause) {
        this.store=store; this.devices=devices; this.leases=leases; this.attempts=attempts;
        this.transport=transport; this.credentials=credentials; this.gates=gates; this.pause=pause;
    }
    public void execute(String jobId,long epoch) {
        var run=store.runByJob(jobId);
        if (run.isEmpty() || !"palo_alto".equals(run.get().vendor()) || run.get().vsId()!=null) {
            leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.FAILED,
                "system:pan-failover-worker","pan_failover_missing_run","RUN_NOT_FOUND");
            return;
        }
        this.jobId=jobId; this.runId=run.get().id(); this.epoch=epoch;
        this.commandIndex=0; this.wrote=false; this.writeInFlight=false;
        if (!leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.EXECUTING,
                "system:pan-failover-worker","pan_failover_start")) return;
        try {
            if (!store.windowValid(runId)) throw new Stop("WINDOW_EXPIRED",0);
            List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(run.get().clusterRef());
            if (members.size()!=2 || members.stream().anyMatch(m -> !"palo_alto".equals(m.vendorHint())))
                throw new Stop("CLUSTER_NOT_ELIGIBLE",0);
            if (!attempts.findByJobAndStep(jobId,0).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
            for (String command:List.of(STATE,SUSPEND,FUNCTIONAL)) gate(command);
            Peer first=peer(members.get(0)),second=peer(members.get(1));
            store.state(runId,"PRECHECK","PRECHECK",null,null,null);
            PanFailoverChecks.State a=read(first),b=read(second);
            checks("pre",first,second,a,b,null);
            Peer formerActive="active".equals(a.role())?first:second;
            Peer newActive=formerActive==first?second:first;
            store.state(runId,"FAILING_OVER","FAILING_OVER",null,null,null);
            wrote=true;
            call(formerActive,SUSPEND);
            if (!waitFor(first,second,newActive,"active",formerActive,"suspended"))
                throw new Stop("FAILOVER_TIMEOUT",1);
            store.state(runId,"SWITCHED","SWITCHED",null,null,null);
            store.state(runId,"POSTCHECK","POSTCHECK",null,null,null);
            a=read(first); b=read(second);
            checks("post",first,second,a,b,formerActive);
            store.state(runId,"RETURNING","RETURNING",null,null,null);
            call(formerActive,FUNCTIONAL);
            if (!waitFor(first,second,newActive,"active",formerActive,"passive"))
                throw new Stop("RETURN_TIMEOUT",1);
            store.state(runId,"DONE","DONE","SUCCEEDED",null,"NO_PROBLEMS_FOUND");
            leases.transitionState(jobId,epoch,JobState.EXECUTING,JobState.COMPLETED,
                "system:pan-failover-worker","pan_failover_done","SUCCEEDED");
        } catch (Stop stopped) { stop(stopped.code,stopped.check); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); stop("INTERRUPTED",0); }
        catch (RuntimeException unexpected) { stop(wrote?"OUTCOME_UNCERTAIN":"PRECHECK_UNAVAILABLE",0); }
    }
    private void stop(String code,int check) {
        store.state(runId,"STOPPED",store.runByJob(jobId).map(JooqCpFailoverRepository.Run::step).orElse("UNKNOWN"),
            code,check==0?null:String.valueOf(check),code);
        leases.transitionState(jobId,epoch,JobState.EXECUTING,writeInFlight || "OUTCOME_UNCERTAIN".equals(code)
            ?JobState.OUTCOME_UNKNOWN:JobState.FAILED,"system:pan-failover-worker","pan_failover_stopped",code);
    }
    private Peer peer(DeviceSummaryRecord summary) {
        var device=devices.find(summary.deviceId()).filter(d -> d.permitsReadCollection())
            .orElseThrow(() -> new Stop("DEVICE_NOT_ELIGIBLE",0));
        var endpoint=devices.findEndpointByDeviceId(summary.deviceId())
            .filter(e -> "pan_xml_api".equalsIgnoreCase(e.transportKind()))
            .orElseThrow(() -> new Stop("ENDPOINT_MISSING",0));
        String identity=devices.findConfirmFacts(summary.deviceId())
            .flatMap(f -> f.recordedIdentityPrimary()).orElseThrow(() -> new Stop("IDENTITY_NOT_VERIFIED",0));
        var credential=credentials.resolve(device.credentialReferenceId());
        var target=new ApiTarget(endpoint.endpointId(),endpoint.addressRef());
        var result=transport.xmlApiCall(target,new XmlApiSpec("POST","keygen","","",
            Map.of("user",credential.username(),"password",new String(credential.password())),Map.of()),Duration.ofSeconds(30));
        String body=result instanceof XmlApiResult.Completed c && c.httpStatus()==200?c.body():"";
        var match=KEY.matcher(body);
        if (!body.contains("status=\"success\"") || !match.find()) throw new Stop("AUTH_UNAVAILABLE",0);
        return new Peer(summary.deviceId(),target,match.group(1),identity);
    }
    private GateResolution.Known gate(String command) {
        String expected=STATE.equals(command)?"pan_inventory_show_ha_state"
            :SUSPEND.equals(command)?"pan_failover_suspend"
            :FUNCTIONAL.equals(command)?"pan_failover_functional":null;
        if (expected==null) throw new Stop("COMMAND_NOT_APPROVED",0);
        var resolved=GateResolver.resolve(new CanonicalCommandKey("palo_alto","pan_firewall",
            "not_applicable","PAN_XML_API",command),java.util.Optional.of(STATE.equals(command)
                ?ActionClass.CLASS_0_READ:ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE),gates);
        if (!(resolved instanceof GateResolution.Known known) || !expected.equals(known.gateId()))
            throw new Stop("COMMAND_GATE_UNAVAILABLE",0);
        return known;
    }
    private String call(Peer peer,String command) {
        var g=gate(command);
        if (!attempts.findByJobAndStep(jobId,commandIndex).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
        String attempt=attempts.insertPreContact(jobId,epoch,commandIndex++,g.gateId(),g.actionClass().id(),1);
        if (attempt==null) throw new Stop("PRE_CONTACT_RECORD_FAILED",0);
        if (!attempts.markBoundaryCrossed(attempt,epoch)) throw new Stop("PRE_CONTACT_UNCERTAIN",0);
        store.command(runId,g.gateId());
        if (!STATE.equals(command)) writeInFlight=true;
        var result=transport.xmlApiCall(peer.target(),new XmlApiSpec("POST","op","","direct_firewall",
            Map.of("cmd",command),Map.of("X-PAN-KEY",peer.key())),Duration.ofSeconds(g.timeoutS()));
        if (!(result instanceof XmlApiResult.Completed completed) || completed.httpStatus()!=200
                || completed.body()==null || completed.body().length()>262144
                || !completed.body().contains("status=\"success\"")) {
            attempts.writeOutcome(attempt,epoch,"FAILED","COMMAND_UNAVAILABLE",false,null,null,null);
            throw new Stop("COMMAND_UNAVAILABLE",0);
        }
        if (!attempts.writeOutcome(attempt,epoch,"MATCHED",null,true,null,null,null))
            throw new Stop("ATTEMPT_RECORD_FAILED",0);
        writeInFlight=false;
        return completed.body();
    }
    private PanFailoverChecks.State read(Peer peer) {
        var state=PanFailoverChecks.parse(call(peer,STATE));
        if (!peer.identity().equals(state.localSerial())) throw new Stop("IDENTITY_NOT_VERIFIED",0);
        return state;
    }
    private void checks(String phase,Peer first,Peer second,PanFailoverChecks.State a,
            PanFailoverChecks.State b,Peer oldActive) {
        String role=oldActive==null
            ?("active".equals(a.role())?PanFailoverChecks.roles(a,b,"active","passive")
                :PanFailoverChecks.roles(a,b,"passive","active"))
            :PanFailoverChecks.roles(a,b,oldActive==first?"suspended":"active",
                oldActive==second?"suspended":"active");
        String[] statuses={role,PanFailoverChecks.relationship(a,b),PanFailoverChecks.links(a,b),PanFailoverChecks.sync(a,b)};
        for (int i=0;i<statuses.length;i++) {
            int no=i+1;
            String derived=no==1?"{\"role\":\""+a.role()+"\"}":"{}";
            store.check(runId,phase,first.id(),null,no,statuses[i],derived);
            derived=no==1?"{\"role\":\""+b.role()+"\"}":"{}";
            store.check(runId,phase,second.id(),null,no,statuses[i],derived);
        }
        for (int i=0;i<statuses.length;i++) if (!"PASS".equals(statuses[i]))
            throw new Stop("CHECK_NOT_READY",i+1);
    }
    private boolean waitFor(Peer first,Peer second,Peer active,String activeRole,Peer other,String otherRole)
            throws InterruptedException {
        for (int i=0;i<MAX_POLLS;i++) {
            var a=read(first); var b=read(second);
            String status=PanFailoverChecks.roles(a,b,active==first?activeRole:otherRole,
                active==second?activeRole:otherRole);
            if ("PASS".equals(status)) return true;
            if (i<MAX_POLLS-1) pause.sleep(POLL);
        }
        return false;
    }
}
