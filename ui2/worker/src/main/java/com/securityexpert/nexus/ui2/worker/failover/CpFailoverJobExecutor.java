package com.securityexpert.nexus.ui2.worker.failover;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/** One failover unit, two trusted sessions, serial commands, no automatic rollback. */
public final class CpFailoverJobExecutor {
    private String requestCluster, requestVs;
    private java.util.Set<String> requestMembers = java.util.Set.of();
    private final FailoverMutationSwitch mutationSwitch;
    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }
    private static final Duration POLL_INTERVAL=Duration.ofSeconds(3);
    private static final int MAX_POLLS=21;
    private static final String STAT="cphaprob stat", TABLE="cphaprob tablestat", IF="cphaprob -a if";
    private static final String ARP="arp -an", TRAFFIC="cat /proc/net/dev";
    private static final String SYNC="cphaprob syncstat", POLICY="fw stat", CPSTAT_POLICY="cpstat -f policy fw";
    private static final String PNOTES="cphaprob -ia list", BONDS="cphaprob show_bond";
    private static final String FAILOVER="cphaprob show_failover", ROUTING="cpstat os -f routing";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String DOWN="clusterXL_admin down", UP="clusterXL_admin up";
    private final JooqCpFailoverRepository store;
    private final DeviceRepository devices;
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceTransport ssh;
    private final GateRegistryPort gates;
    private final Pause pause;
    private final Duration commandPause;
    private final java.util.function.LongSupplier nanoTime;
    private int polls;
    private String jobId,runId,vsId;
    private long epoch;
    private int commandIndex;
    private boolean wrote;
    private JooqCpFailoverRepository.Dispatch dispatch;
    private boolean readiness;
    private Stop readinessFailure;
    private ReadinessShapeLog shapes;

    private static final class Member {
        private final String id;
        private final TransportSession session;
        private boolean identityVerified=true;
        private final long openedAtNanos=System.nanoTime();
        private int sessionCommandIndex;
        private String localId;
        Member(String id,TransportSession session) { this.id=id; this.session=session; }
        String id() { return id; }
        TransportSession session() { return session; }
    }
    private record Measure(CpFailoverChecks.State state, Set<String> table,
            CpFailoverChecks.Interfaces interfaces, int arp, double traffic,
            String policyName, String cpstatPolicyName, CpFailoverChecks.Routing routing) {}
    private record Pair(Measure a, Measure b) {
        Measure forMember(Member member,Member first) { return member==first?a:b; }
    }
    private static final class Stop extends RuntimeException {
        final String code; final int check; final String status;
        Stop(String code,int check) { this(code,check,"FAIL"); }
        Stop(String code,int check,String status) { super(code); this.code=code; this.check=check; this.status=status; }
    }

    public CpFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices,
            JobLeaseRepository leases, JobStepAttemptRepository attempts,DeviceTransport ssh,
            GateRegistryPort gates) {
        this(store,devices,leases,attempts,ssh,gates,d -> Thread.sleep(d.toMillis()),Duration.ofSeconds(2));
    }
    public CpFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices,
            JobLeaseRepository leases, JobStepAttemptRepository attempts,DeviceTransport ssh,
            GateRegistryPort gates,Pause pause) {
        this(store,devices,leases,attempts,ssh,gates,pause,Duration.ofSeconds(2));
    }
    public CpFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices,
            JobLeaseRepository leases, JobStepAttemptRepository attempts,DeviceTransport ssh,
            GateRegistryPort gates,Pause pause,Duration commandPause) {
        this(store,devices,leases,attempts,ssh,gates,pause,commandPause,
            FailoverMutationSwitch.fromEnvironment());
    }
    public CpFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices,
            JobLeaseRepository leases, JobStepAttemptRepository attempts,DeviceTransport ssh,
            GateRegistryPort gates,Pause pause,Duration commandPause,
            FailoverMutationSwitch mutationSwitch) {
        this(store,devices,leases,attempts,ssh,gates,pause,commandPause,mutationSwitch,System::nanoTime);
    }
    CpFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices,
            JobLeaseRepository leases, JobStepAttemptRepository attempts,DeviceTransport ssh,
            GateRegistryPort gates,Pause pause,Duration commandPause,FailoverMutationSwitch mutationSwitch,
            java.util.function.LongSupplier nanoTime) {
        this.nanoTime=nanoTime;
        this.mutationSwitch=java.util.Objects.requireNonNull(mutationSwitch);
        this.store=store; this.devices=devices; this.leases=leases; this.attempts=attempts;
        this.ssh=ssh; this.gates=gates; this.pause=pause; this.commandPause=commandPause;
    }

    public void execute(String jobId,long epoch) {
        var run=store.runByJob(jobId);
        if(run.isEmpty()) {
            leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.FAILED,"system:cp-failover-worker",
                "cp_failover_missing_run","RUN_NOT_FOUND"); return;
        }
        this.jobId=jobId; this.runId=run.get().id(); this.vsId=run.get().vsId();
        this.readiness="READINESS".equals(run.get().kind());
        this.readinessFailure=null;
        this.shapes=new ReadinessShapeLog("check_point");
        this.epoch=epoch; this.commandIndex=0; this.polls=0; this.wrote=false; this.dispatch=null;
        if(!leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.EXECUTING,
                "system:cp-failover-worker","cp_failover_start")) return;
        Member first=null,second=null;
        try {
            if (!readiness && !mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED,0);
            if(!readiness && !store.windowValid(runId)) throw new Stop("WINDOW_EXPIRED",0);
            List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(run.get().clusterRef());
            if(members.size()!=2 || members.get(0).deviceId().equals(members.get(1).deviceId())
                    || members.stream().anyMatch(m -> !"check_point".equals(m.vendorHint())))
                throw new Stop("CLUSTER_NOT_ELIGIBLE",0);
            if (!readiness) {
                requestCluster=run.get().clusterRef(); requestVs=run.get().vsId();
                requestMembers=members.stream().map(DeviceSummaryRecord::deviceId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
                requireMutationAdmission(false);
            }
            if(!attempts.findByJobAndStep(jobId,0).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
            for(String command:List.of(STAT,TABLE,IF,ARP,TRAFFIC,SYNC,POLICY,PNOTES,BONDS,FAILOVER,ROUTING)) gate(command);
            if (!readiness) { gate(CPSTAT_POLICY); gate(DOWN); gate(UP); }
            first=connect(members.get(0)); second=connect(members.get(1));
            if(first.session().presentedIdentity().equals(second.session().presentedIdentity()))
                throw new Stop("OBSERVERS_NOT_DISTINCT",0);
            state("PRECHECK","PRECHECK",null,null,null);
            Pair before=checks("pre",first,second,null,null);
            if (readiness) {
                finishReadiness("READY",0,"PASS");
                return;
            }
            Member formerActive="ACTIVE".equals(before.a().state().localRole())?first:second;
            Member formerStandby=formerActive==first?second:first;
            state("FAILING_OVER","FAILING_OVER",null,null,null);
            command(formerActive,DOWN);
            if(!waitFor(first,second,formerStandby,"ACTIVE",formerActive,"DOWN"))
                throw new Stop("FAILOVER_TIMEOUT",1);
            confirmDispatch();
            state("SWITCHED","SWITCHED",null,null,null);
            state("POSTCHECK","POSTCHECK",null,null,null);
            checks("post",first,second,before,formerActive);
            if(polls>=MAX_POLLS) throw new Stop("POLL_BUDGET_EXHAUSTED",1);
            state("RETURNING","RETURNING",null,null,null);
            command(formerActive,UP);
            if(!waitFor(first,second,formerStandby,"ACTIVE",formerActive,"STANDBY"))
                throw new Stop("RETURN_TIMEOUT",1);
            confirmDispatch();
            state("POSTCHECK","POST_RETURN",null,null,null);
            checks("post_return",first,second,before,formerActive);
            state("DONE","DONE","SUCCEEDED",null,"NO_PROBLEMS_FOUND");
        } catch (JooqCpFailoverRepository.NotSent notSent) {
            stop("MUTATION_DISABLED_BEFORE_SEND",0);
        } catch (Stop stopped) {
            if (readiness) finishReadiness(stopped.code,stopped.check,stopped.check==0?"UNKNOWN":stopped.status);
            else stop(stopped.code,stopped.check);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (readiness) finishReadiness("INTERRUPTED",0,"UNKNOWN"); else stop("INTERRUPTED",0);
        } catch (RuntimeException unexpected) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unexpected);
            if (readiness) finishReadiness("COLLECTION_FAILED",0,"UNKNOWN");
            else stop(wrote?"OUTCOME_UNCERTAIN":"PRECHECK_UNAVAILABLE",0);
        } finally {
            if(first!=null) ssh.disconnect(first.session());
            if(second!=null) ssh.disconnect(second.session());
        }
    }
    private void requireMutationAdmission(boolean possibleSend) {
        if (!mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED,0);
        String decision=store.mutationAdmission(runId,requestCluster,requestVs,"check_point",requestMembers,possibleSend,jobId,epoch);
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
        String stopCode=code;
        if (readinessFailure!=null && (!"FAIL".equals(status) || "FAIL".equals(readinessFailure.status))) {
            code=readinessFailure.code; check=readinessFailure.check; status=readinessFailure.status;
        }
        String outcome="PASS".equals(status)?"READY":"FAIL".equals(status)?"NOT_READY":"UNKNOWN";
        state("DONE","DONE",outcome,check==0?null:checkName(check),"COMMAND_UNAVAILABLE".equals(stopCode)?stopCode:code);
        leases.transitionState(jobId,epoch,JobState.EXECUTING,JobState.COMPLETED,
            "system:cp-failover-worker","cp_readiness_done",outcome);
    }
    private static String checkName(int check) {
        return switch (check) {
            case 1 -> "Cluster state"; case 2 -> "Cluster IP table"; case 3 -> "Cluster interfaces";
            case 5 -> "ARP"; case 6 -> "Connections"; case 8 -> "Traffic rate";
            case 9 -> "State synchronization"; case 10 -> "Installed policy parity";
            case 11 -> "Critical devices"; case 12 -> "Bond interfaces";
            case 13 -> "Last failover"; case 14 -> "Routing"; default -> "Pre-check";
        };
    }
    private Member connect(DeviceSummaryRecord summary) {
        var device=devices.find(summary.deviceId()).filter(d -> d.permitsReadCollection())
            .orElseThrow(() -> new Stop("DEVICE_NOT_ELIGIBLE",0));
        var endpoint=devices.findEndpointByDeviceId(summary.deviceId())
            .filter(e -> summary.deviceId().equals(e.deviceId()) && "ssh_exec".equalsIgnoreCase(e.transportKind()))
            .orElseThrow(() -> new Stop("ENDPOINT_MISSING",0));
        String identity=devices.findConfirmFacts(summary.deviceId()).flatMap(f -> f.recordedIdentityPrimary())
            .filter(value -> !value.isBlank()).orElse(null);
        if(identity==null && !readiness) throw new Stop("IDENTITY_NOT_VERIFIED",0);
        String host=com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.host(endpoint.addressRef());
        int port=com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.port(endpoint.addressRef(),22);
        var result=ssh.connect(new ConnectionTarget(endpoint.endpointId(),host,port),
            new ConnectSpec(device.credentialReferenceId(),
                PersistedManagementEndpointTrustResolver.scopeRef(host,port),java.util.Optional.empty()),Duration.ofSeconds(30));
        if(!(result instanceof ConnectResult.Authenticated auth)) throw new Stop("TRUSTED_CONNECTION_REQUIRED",0);
        if(auth.session().presentedIdentity().filter(value -> !value.isBlank()).isEmpty()
                || identity!=null && !auth.session().presentedIdentity().filter(identity::equals).isPresent()) {
            ssh.disconnect(auth.session());
            throw new Stop("IDENTITY_NOT_VERIFIED",0);
        }
        Member member=new Member(summary.deviceId(),auth.session());
        if(identity==null) {
            member.identityVerified=false;
            readinessFailure=new Stop("IDENTITY_NOT_RECORDED",1,"UNKNOWN");
        }
        // Session provenance is identity-verified for mutations; missing enrollment is explicit in readiness.
        return member;
    }
    private GateResolution.Known gate(String command) {
        boolean write=DOWN.equals(command)||UP.equals(command);
        if (write && !mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED,0);
        if(!writeAllowed(readiness ? "READINESS" : "FAILOVER", command))
            throw new Stop("READINESS_WRITE_REFUSED",0);
        String key=vsId==null?(CPSTAT_POLICY.equals(command)?"bash -lc '"+command+"'":command):"bash -lc 'vsenv <VSID> && "+command+"'";
        var resolution=GateResolver.resolve(new CanonicalCommandKey("check_point","cp_gaia_gateway",
            "expert","SSH_EXEC",key),java.util.Optional.of(write
                ?ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE:ActionClass.CLASS_0_READ),gates);
        if(!(resolution instanceof GateResolution.Known known)) throw new Stop("COMMAND_GATE_UNAVAILABLE",0);
        String expected=switch(command) {
            case STAT -> vsId==null?"cp_inventory_cphaprob_stat":"cp_inventory_vsid_cphaprob_stat";
            case IF -> vsId==null?"cp_inventory_cphaprob_a_if":"cp_inventory_vsid_cphaprob_a_if";
            case TABLE -> "cp_failover_tablestat";
            case ARP -> "cp_failover_arp";
            case TRAFFIC -> "cp_failover_traffic";
            case SYNC -> "cp_failover_syncstat";
            case POLICY -> "cp_failover_fw_stat";
            case CPSTAT_POLICY -> "cp_policy_install_cpstat";
            case PNOTES -> "cp_failover_pnotes";
            case BONDS -> "cp_failover_bonds";
            case FAILOVER -> "cp_failover_last_event";
            case ROUTING -> "cp_failover_routing";
            case DOWN -> "cp_failover_down";
            case UP -> "cp_failover_up";
            default -> throw new Stop("COMMAND_NOT_APPROVED",0);
        }+(vsId!=null && !STAT.equals(command) && !IF.equals(command)?"_vsid":"");
        if(!expected.equals(known.gateId())) throw new Stop("COMMAND_GATE_MISMATCH",0);
        return known;
    }
    static boolean writeAllowed(String kind,String command) {
        return !"READINESS".equals(kind) || !(DOWN.equals(command) || UP.equals(command));
    }
    private String command(Member member,String command) throws InterruptedException {
        var g=gate(command);
        if (commandIndex > 0 && !commandPause.isZero()) pause.sleep(commandPause);
        if(vsId!=null && !vsId.matches("[0-9]{1,10}")) throw new Stop("VSID_INVALID",0);
        if (DOWN.equals(command) || UP.equals(command)) {
            if (!mutationSwitch.enabled()) throw new Stop(FailoverMutationSwitch.DISABLED,0);
            requireMutationAdmission(false);
            dispatch=store.prepareDispatch(runId,epoch,commandIndex++,member.id(),g.gateId(),g.actionClass().id());
            wrote=true;
            String literal=vsId==null?"bash -lc '"+command+"'":"bash -lc 'vsenv "+vsId+" && "+command+"'";
            boolean replied=store.dispatch(dispatch,() -> {
                if (!mutationSwitch.enabled()) throw new JooqCpFailoverRepository.NotSent();
                try {
                    ExecResult result=ssh.exec(member.session(),new ExecSpec(literal,false),Duration.ofSeconds(g.timeoutS()));
                    return result instanceof ExecResult.Completed c && c.exitStatus()==0
                        && c.output()!=null && c.output().length()<=262144 && !commandUnavailable(c.output());
                } catch (RuntimeException uncertain) { return false; }
            });
            if (!replied) throw new Stop("OUTCOME_UNCERTAIN",0);
            return "";
        }
        if(!attempts.findByJobAndStep(jobId,commandIndex).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
        String attempt=attempts.insertPreContact(jobId,epoch,commandIndex++,g.gateId(),g.actionClass().id(),1);
        if(attempt==null) throw new Stop("PRE_CONTACT_RECORD_FAILED",0);
        if(!attempts.markBoundaryCrossed(attempt,epoch)) throw new Stop("PRE_CONTACT_UNCERTAIN",0);
        store.command(runId,g.gateId());
        // Measured 2026-09-30: on the fleet's gateways a plain exec of 'cphaprob stat' exits 0 with no output -- the
        // exec shell lacks the Check Point environment. Inventory already falls back to a login shell; every CP command
        // here (reads and clusterXL_admin alike) runs in one, as the vsenv-wrapped VS form always did.
        String literal=vsId==null?"bash -lc '"+command+"'":"bash -lc 'vsenv "+vsId+" && "+command+"'";
        boolean pty=IF.equals(command);
        int sessionCommandIndex=++member.sessionCommandIndex;
        long sessionElapsedMs=(System.nanoTime()-member.openedAtNanos)/1_000_000;
        ExecResult result=ssh.exec(member.session(),new ExecSpec(literal,pty),Duration.ofSeconds(g.timeoutS()));
        if(!(result instanceof ExecResult.Completed completed) || completed.exitStatus()!=0
                || commandUnavailable(completed.output())) {
            attempts.writeOutcome(attempt,epoch,"FAILED","COMMAND_UNAVAILABLE",false,null,null,null);
            throw new Stop("COMMAND_UNAVAILABLE",0);
        }
        if(completed.output()==null || completed.output().length()>262144)
            throw new Stop("OUTPUT_UNAVAILABLE",0);
        if(!attempts.writeOutcome(attempt,epoch,"MATCHED",null,true,null,null,null))
            throw new Stop("ATTEMPT_RECORD_FAILED",0);
        int check=switch(command) {
            case STAT -> 1; case TABLE -> 2; case IF -> 3; case ARP -> 5;
            case TRAFFIC -> 8; case SYNC -> 9; case POLICY,CPSTAT_POLICY -> 10;
            case PNOTES -> 11; case BONDS -> 12; case FAILOVER -> 13; case ROUTING -> 14;
            default -> 0;
        };
        if (check!=0) shapes.capture(check,completed.output(),pty,sessionCommandIndex,sessionElapsedMs);
        return check==0?completed.output():normalizeRead(command,completed.output(),vsId,shapes);
    }
    static boolean commandUnavailable(String output) {
        if(output==null) return false;
        String lower=output.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("command not found") || lower.contains("no such file or directory")
            || lower.contains("permission denied");
    }
    /** Remove only the documented transport preamble; never mask command errors. */
    static String normalizeRead(String command,String output,String vsId,ReadinessShapeLog shapes) {
        if(output==null) return null;
        String context=vsId==null?"(?!)":"Context is set to Virtual Device [^\\r\\n]+ \\(ID "
            +java.util.regex.Pattern.quote(vsId)+"\\)\\.[ \t]*(?:\\r?\\n|$)";
        var preamble=java.util.regex.Pattern.compile(
            "\\A(?:[ \\t]*\\r?\\n|Warning! [^\\r\\n]*\\.[ \t]*(?:\\r?\\n|$)|"+context+")+"
        ).matcher(output);
        if(!preamble.find()) return output;
        String remaining=output.substring(preamble.end());
        boolean banner=preamble.group().lines().anyMatch(line -> line.startsWith("Warning!"));
        // A banner alone must not become an apparently valid empty ARP table.
        if(banner && (remaining.isBlank() && !preamble.group().contains("Context is set to Virtual Device ")
                || !readable(command,remaining))) return output;
        if(banner) shapes.logBannerStripped();
        return remaining;
    }

    private static boolean readable(String command,String output) {
        return switch(command) {
            case STAT -> !"UNKNOWN".equals(CpFailoverChecks.state(output).mode());
            case TABLE -> !CpFailoverChecks.ipTable(output).isEmpty();
            case IF -> CpFailoverChecks.interfaces(output).ccpPresent();
            case ARP -> CpFailoverChecks.arpCount(output)>=0;
            case TRAFFIC -> CpFailoverChecks.bytesByInterface(output).reason()==CpFailoverChecks.TrafficReason.NONE;
            case SYNC -> !"UNKNOWN".equals(CpFailoverChecks.syncStatus(output));
            case POLICY -> !"UNKNOWN".equals(CpFailoverChecks.policy(output).status());
            case CPSTAT_POLICY -> !"UNKNOWN".equals(CpFailoverChecks.cpstatPolicy(output).status());
            case PNOTES -> !"UNKNOWN".equals(CpFailoverChecks.pnotes(output).status());
            case BONDS -> !"UNKNOWN".equals(CpFailoverChecks.bonds(output));
            case FAILOVER -> CpFailoverChecks.lastFailover(output,Instant.now()).lastFailoverAt()!=null;
            case ROUTING -> CpFailoverChecks.routing(output)!=null;
            default -> false;
        };
    }
    private String recordSync(String phase,Member member,String output) {
        if(output==null) return null;
        var previous=store.previousReadinessSync(runId,member.id(),vsId);
        Map<String,Object> counters=Map.of();
        if(previous.isPresent()) try {
            counters=JSON.readValue(previous.get().derived(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        } catch(JsonProcessingException invalid) {
            record(phase,member,9,"UNKNOWN",json(CpFailoverChecks.syncInformation(output)));
            return "UNKNOWN";
        }
        var assessment=CpFailoverChecks.readinessSync(output,counters,
            previous.map(JooqCpFailoverRepository.Check::observedAt).orElse(null));
        record(phase,member,9,assessment.status(),json(assessment.derived()));
        return assessment.status();
    }
    private CpFailoverChecks.State readState(Member member) throws InterruptedException {
        var state=CpFailoverChecks.state(command(member,STAT),vsId);
        if(member.localId!=null && !member.localId.equals(state.localId())) return CpFailoverChecks.state(null);
        if(state.localId()!=null) member.localId=state.localId();
        return state;
    }
    private Pair checks(String phase,Member first,Member second,Pair before,Member oldActive) throws InterruptedException {
        boolean switched="post".equals(phase);
        var a=readState(first);
        var b=readState(second);
        boolean states=before==null?CpFailoverChecks.corroborated(a,b)
            : roles(a,b,oldActive==first?second:first,"ACTIVE",oldActive,switched?"DOWN":"STANDBY",first,second);
        String stateStatus=states?"PASS":a.supportedMode() && b.supportedMode()?"FAIL":"UNKNOWN";
        for(Member member:List.of(first,second)) {
            var evidence=new java.util.HashMap<>(CpFailoverChecks.stateEvidence(member==first?a:b));
            evidence.put("identity",member.identityVerified?"MATCH":"NOT_EVALUABLE");
            if(!member.identityVerified) evidence.put("reason","IDENTITY_NOT_RECORDED");
            record(phase,member,1,!member.identityVerified && "PASS".equals(stateStatus)?"UNKNOWN":stateStatus,json(evidence));
        }
        if(!states) checkFailed("CLUSTER_STATE_NOT_READY",1,stateStatus);
        double[] rates=before==null?null:trafficWindow(phase,first,second,
            before.a().interfaces(),before.b().interfaces(),before,oldActive);
        var ta=CpFailoverChecks.ipTable(command(first,TABLE));
        var tb=CpFailoverChecks.ipTable(command(second,TABLE));
        boolean tables=CpFailoverChecks.twoTableMembers(ta) && ta.equals(tb);
        String tableStatus=tables?"PASS":!ta.isEmpty() && !tb.isEmpty() && !ta.equals(tb)?"FAIL":"UNKNOWN";
        shapes.logTables(tableStatus,ta,tb);
        var difference=new java.util.HashMap<>(CpFailoverChecks.tableDifference(ta,tb));
        if(!tables) difference.put("reason",ta.isEmpty() || tb.isEmpty() || ta.equals(tb)?"TABLE_UNRECOGNIZED":"tables differ");
        difference.put("entries",ta.size());
        record(phase,first,2,tableStatus,json(difference));
        difference.put("entries",tb.size());
        record(phase,second,2,tableStatus,json(difference));
        if(!tables) checkFailed("CLUSTER_IP_TABLE_MISMATCH",2,tableStatus);
        var ia=CpFailoverChecks.interfaces(command(first,IF));
        var ib=CpFailoverChecks.interfaces(command(second,IF));
        record(phase,first,3,ia.healthy()?"PASS":ia.ccpPresent()?"FAIL":"UNKNOWN",json(Map.of("up",ia.names().size(),"required",ia.required())));
        record(phase,second,3,ib.healthy()?"PASS":ib.ccpPresent()?"FAIL":"UNKNOWN",json(Map.of("up",ib.names().size(),"required",ib.required())));
        if(!ia.healthy() || !ib.healthy()) checkFailed("INTERFACE_NOT_READY",3,
            !ia.ccpPresent() || !ib.ccpPresent() ? "UNKNOWN" : "FAIL");
        if(before!=null && (!ia.trafficNames().equals(before.a().interfaces().trafficNames())
                || !ib.trafficNames().equals(before.b().interfaces().trafficNames()))) {
            for(Member member:List.of(first,second)) record(phase,member,8,"UNKNOWN","{\"reason\":\"TRAFFIC_SCOPE_CHANGED\"}");
            checkFailed("TRAFFIC_SCOPE_CHANGED",8,"UNKNOWN");
        }
        int arpA=CpFailoverChecks.arpCount(command(first,ARP));
        int arpB=CpFailoverChecks.arpCount(command(second,ARP));
        Member active=before==null?("ACTIVE".equals(a.localRole())?first:second):(oldActive==first?second:first);
        // Contract §15 (PO 2026-09-30): ARP is information only -- counts recorded, never blocking.
        String arpStatus=arpA<0 || arpB<0?"UNKNOWN":"PASS";
        record(phase,first,5,arpStatus,arpA<0?"{}":"{\"count\":"+arpA+"}");
        record(phase,second,5,arpStatus,arpB<0?"{}":"{\"count\":"+arpB+"}");
        var continuity=CpFailoverChecks.sessionContinuity();
        for(Member member:List.of(first,second))
            record(phase,member,6,continuity.status(),json(continuity.derived()));
        String syncOutputA=!switched || active==first?command(first,SYNC):null;
        String syncOutputB=!switched || active==second?command(second,SYNC):null;
        String syncA=recordSync(phase,first,syncOutputA);
        String syncB=recordSync(phase,second,syncOutputB);
        if("FAIL".equals(syncA) || "FAIL".equals(syncB) || "UNKNOWN".equals(syncA) || "UNKNOWN".equals(syncB))
            checkFailed("STATE_SYNC_NOT_READY",9,"FAIL".equals(syncA) || "FAIL".equals(syncB)?"FAIL":"UNKNOWN");
        var policyA=CpFailoverChecks.policy(command(first,POLICY));
        var policyB=CpFailoverChecks.policy(command(second,POLICY));
        var parity=CpFailoverChecks.policyParity(policyA,policyB,
            before==null?null:before.a().policyName(),before==null?null:before.b().policyName());
        var cpstatA=readiness?policyA:CpFailoverChecks.cpstatPolicy(command(first,CPSTAT_POLICY));
        var cpstatB=readiness?policyB:CpFailoverChecks.cpstatPolicy(command(second,CPSTAT_POLICY));
        var cpstatParity=CpFailoverChecks.policyParity(cpstatA,cpstatB,
            before==null?null:before.a().cpstatPolicyName(),before==null?null:before.b().cpstatPolicyName());
        String policyStatus="FAIL".equals(parity.status()) || "FAIL".equals(cpstatParity.status())?"FAIL"
            :"PASS".equals(parity.status()) && "PASS".equals(cpstatParity.status())?"PASS":"UNKNOWN";
        String policyEvidence=readiness?json(Map.of("fwStat",parity.derived()))
            :json(Map.of("fwStat",parity.derived(),"cpstat",cpstatParity.derived()));
        record(phase,first,10,policyStatus,policyEvidence);
        record(phase,second,10,policyStatus,policyEvidence);
        if(!"PASS".equals(policyStatus)) checkFailed("POLICY_NOT_MATCHED",10,policyStatus);
        for(Member member:List.of(first,second)) {
            var pnotes=CpFailoverChecks.pnotes(command(member,PNOTES),switched && member==oldActive);
            record(phase,member,11,pnotes.status(),json(Map.of("pnotes",pnotes.names())));
            if(!"PASS".equals(pnotes.status())) checkFailed("PNOTES_NOT_READY",11,pnotes.status());
            String bondOutput=command(member,BONDS);
            String bonds=CpFailoverChecks.bonds(bondOutput);
            record(phase,member,12,bonds,json(Map.of("noneConfigured",
                bondOutput.strip().equals("No bond interfaces are configured."))));
            if(!"PASS".equals(bonds)) checkFailed("BOND_NOT_READY",12,bonds);
        }
        for(Member member:List.of(first,second)) {
            String output=null;
            try { output=command(member,FAILOVER); }
            catch(Stop unavailable) {
                if(readiness || !"COMMAND_UNAVAILABLE".equals(unavailable.code)) throw unavailable;
            }
            var event=CpFailoverChecks.lastFailover(output,Instant.now());
            record(phase,member,13,event.status(),event.lastFailoverAt()==null?"{}":json(Map.of(
                "lastFailoverAt",event.lastFailoverAt(),"assumedTimeZone","Europe/Istanbul")));
        }
        CpFailoverChecks.Routing routingA=null,routingB=null;
        for(Member member:List.of(first,second)) {
            if(switched && member!=active) continue;
            var routing=CpFailoverChecks.routing(command(member,ROUTING));
            if(member==first) routingA=routing; else routingB=routing;
            String status=routing==null?"UNKNOWN":before==null?"PASS"
                :member==active?(routing.defaultRoute() && routing.count()==before.forMember(oldActive,first).routing().count()?"PASS":"FAIL")
                :routing.equals(before.forMember(member,first).routing())?"PASS":"FAIL";
            record(phase,member,14,status,routing==null?"{}":json(Map.of(
                "routeCount",routing.count(),"defaultRoute",routing.defaultRoute())));
            if(!"PASS".equals(status)) checkFailed("ROUTING_NOT_READY",14,status);
        }
        if(before==null) rates=trafficWindow(phase,first,second,ia,ib,null,active);
        else if(!roles(readState(first),readState(second),active,"ACTIVE",oldActive,
                switched?"DOWN":"STANDBY",first,second)) throw new Stop("UNEXPECTED_ROLES",1);
        return new Pair(new Measure(a,ta,ia,arpA,rates[0],policyA.name(),cpstatA.name(),routingA),
            new Measure(b,tb,ib,arpB,rates[1],policyB.name(),cpstatB.name(),routingB));
    }
    private CpFailoverChecks.TrafficSample trafficSample(Member member) throws InterruptedException {
        try { return CpFailoverChecks.bytesByInterface(command(member,TRAFFIC)); }
        catch(Stop unavailable) {
            if(!Set.of("COMMAND_UNAVAILABLE","OUTPUT_UNAVAILABLE").contains(unavailable.code)) throw unavailable;
            return CpFailoverChecks.bytesByInterface(null);
        }
    }
    private double[] trafficWindow(String phase,Member first,Member second,
            CpFailoverChecks.Interfaces ia,CpFailoverChecks.Interfaces ib,Pair before,Member oldActive)
            throws InterruptedException {
        var preA=trafficSample(first);
        long startA=nanoTime.getAsLong();
        var preB=trafficSample(second);
        long startB=nanoTime.getAsLong();
        pause.sleep(Duration.ofSeconds(5));
        var postA=trafficSample(first);
        long elapsedA=nanoTime.getAsLong()-startA;
        var postB=trafficSample(second);
        long elapsedB=nanoTime.getAsLong()-startB;
        var measurementA=CpFailoverChecks.trafficBytesPerSecond(preA,postA,ia.trafficNames(),elapsedA);
        var measurementB=CpFailoverChecks.trafficBytesPerSecond(preB,postB,ib.trafficNames(),elapsedB);
        double rateA=measurementA.bytesPerSecond(),rateB=measurementB.bytesPerSecond();
        double baseline=before==null?(oldActive==first?rateA:rateB):before.forMember(oldActive,first).traffic();
        double current=before==null?baseline:oldActive==first?rateB:rateA;
        String status=rateA<0 || rateB<0?"UNKNOWN":CpFailoverChecks.trafficStatus(baseline,current);
        record(phase,first,8,status,json(measurementA.derived(elapsedA)));
        record(phase,second,8,status,json(measurementB.derived(elapsedB)));
        if(!"PASS".equals(status)) checkFailed("TRAFFIC_BELOW_TOLERANCE",8,status);
        return new double[]{rateA,rateB};
    }
    private void checkFailed(String code,int check,String status) {
        if (!readiness) throw new Stop(code,check,status);
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(JsonProcessingException invalid) { throw new IllegalStateException("Derived projection unavailable"); }
    }
    private void record(String phase,Member member,int no,String status,String derived) {
        if (readiness && no!=5 && no!=6 && no!=13 && !"PASS".equals(status)
                && (readinessFailure==null || !"FAIL".equals(readinessFailure.status) && "FAIL".equals(status)))
            readinessFailure=new Stop("CHECK_NOT_READY",no,status);
        shapes.logUnknown(no,status);
        store.check(runId,phase,member.id(),vsId,no,status,derived);
    }
    private boolean waitFor(Member first,Member second,Member active,String activeRole,Member other,
            String otherRole) throws InterruptedException {
        for(;polls<MAX_POLLS;) {
            polls++;
            var a=readState(first);
            var b=readState(second);
            if(roles(a,b,active,activeRole,other,otherRole,first,second)) return true;
            if("STANDBY".equals(otherRole) && ("ACTIVE".equals((other==first?a:b).localRole())
                    || "STANDBY".equals((active==first?a:b).localRole()))) throw new Stop("UNEXPECTED_ROLES",1);
            if(polls<MAX_POLLS) pause.sleep(POLL_INTERVAL);
        }
        return false;
    }
    private static boolean roles(CpFailoverChecks.State a,CpFailoverChecks.State b,Member active,
            String activeRole,Member other,String otherRole,Member first,Member second) {
        return a.supportedMode() && b.supportedMode() && a.members().size()==2
            && "PASS".equals(CpFailoverChecks.reciprocal(a,b))
            && (active==first?activeRole:otherRole).equals(a.localRole())
            && (active==second?activeRole:otherRole).equals(b.localRole());
    }
}
