package com.securityexpert.nexus.ui2.worker.failover;

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
    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }
    private static final Duration POLL_INTERVAL=Duration.ofSeconds(3);
    private static final int MAX_POLLS=21;
    private static final String STAT="cphaprob stat", TABLE="cphaprob tablestat", IF="cphaprob -a if";
    private static final String ARP="arp -an", CONN="fw tab -t connections -s", TRAFFIC="cat /proc/net/dev";
    private static final String SYNC="cphaprob syncstat", POLICY="fw stat";
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
    private String jobId,runId,vsId;
    private long epoch;
    private int commandIndex;
    private boolean wrote;
    private boolean writeInFlight;
    private boolean readiness;
    private Stop readinessFailure;
    private ReadinessShapeLog shapes;

    private record Member(String id, TransportSession session) {}
    private record Measure(CpFailoverChecks.State state, Set<String> table,
            CpFailoverChecks.Interfaces interfaces, int arp, CpFailoverChecks.Connections connections, long traffic,
            String policyName, CpFailoverChecks.Routing routing) {}
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
        this.epoch=epoch; this.commandIndex=0; this.wrote=false; this.writeInFlight=false;
        if(!leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.EXECUTING,
                "system:cp-failover-worker","cp_failover_start")) return;
        Member first=null,second=null;
        try {
            if(!readiness && !store.windowValid(runId)) throw new Stop("WINDOW_EXPIRED",0);
            List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(run.get().clusterRef());
            if(members.size()!=2 || members.stream().anyMatch(m -> !"check_point".equals(m.vendorHint())))
                throw new Stop("CLUSTER_NOT_ELIGIBLE",0);
            if(!attempts.findByJobAndStep(jobId,0).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
            for(String command:List.of(STAT,TABLE,IF,ARP,CONN,TRAFFIC,SYNC,POLICY,PNOTES,BONDS,FAILOVER,ROUTING)) gate(command);
            if (!readiness) { gate(DOWN); gate(UP); }
            first=connect(members.get(0)); second=connect(members.get(1));
            store.state(runId,"PRECHECK","PRECHECK",null,null,null);
            Pair before=checks("pre",first,second,null,null);
            if (readiness) {
                finishReadiness("READY",0,"PASS");
                return;
            }
            Member formerActive="ACTIVE".equals(before.a().state().localRole())?first:second;
            Member formerStandby=formerActive==first?second:first;
            store.state(runId,"FAILING_OVER","FAILING_OVER",null,null,null);
            wrote=true;
            command(formerActive,DOWN);
            if(!waitFor(first,second,formerStandby,"ACTIVE",formerActive,"DOWN"))
                throw new Stop("FAILOVER_TIMEOUT",1);
            store.state(runId,"SWITCHED","SWITCHED",null,null,null);
            store.state(runId,"POSTCHECK","POSTCHECK",null,null,null);
            checks("post",first,second,before,formerActive);
            store.state(runId,"RETURNING","RETURNING",null,null,null);
            command(formerActive,UP);
            if(!waitFor(first,second,formerStandby,"ACTIVE",formerActive,"STANDBY"))
                throw new Stop("RETURN_TIMEOUT",1);
            store.state(runId,"DONE","DONE","SUCCEEDED",null,"NO_PROBLEMS_FOUND");
            leases.transitionState(jobId,epoch,JobState.EXECUTING,JobState.COMPLETED,
                "system:cp-failover-worker","cp_failover_done","SUCCEEDED");
        } catch (Stop stopped) {
            if (readiness) finishReadiness(stopped.code,stopped.check,stopped.check==0?"UNKNOWN":stopped.status);
            else stop(stopped.code,stopped.check);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (readiness) finishReadiness("INTERRUPTED",0,"UNKNOWN"); else stop("INTERRUPTED",0);
        } catch (RuntimeException unexpected) {
            if (readiness) finishReadiness("COLLECTION_FAILED",0,"UNKNOWN");
            else stop(wrote?"OUTCOME_UNCERTAIN":"PRECHECK_UNAVAILABLE",0);
        } finally {
            if(first!=null) ssh.disconnect(first.session());
            if(second!=null) ssh.disconnect(second.session());
        }
    }
    private void stop(String code,int check) {
        store.state(runId,"STOPPED",store.runByJob(jobId).map(JooqCpFailoverRepository.Run::step).orElse("UNKNOWN"),
            code,check==0?null:String.valueOf(check),code);
        leases.transitionState(jobId,epoch,JobState.EXECUTING,writeInFlight || "OUTCOME_UNCERTAIN".equals(code)
            ?JobState.OUTCOME_UNKNOWN:JobState.FAILED,"system:cp-failover-worker","cp_failover_stopped",code);
    }
    private void finishReadiness(String code,int check,String status) {
        if (readinessFailure!=null && (!"FAIL".equals(status) || "FAIL".equals(readinessFailure.status))) {
            code=readinessFailure.code; check=readinessFailure.check; status=readinessFailure.status;
        }
        String outcome="PASS".equals(status)?"READY":"FAIL".equals(status)?"NOT_READY":"UNKNOWN";
        store.state(runId,"DONE","DONE",outcome,check==0?null:checkName(check),code);
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
            .orElseThrow(() -> new Stop("ENDPOINT_MISSING",0));
        String address=endpoint.addressRef(); int colon=address.lastIndexOf(':');
        String host=colon<0?address:address.substring(0,colon);
        int port=22;
        if(colon>=0) try { port=Integer.parseInt(address.substring(colon+1)); }
            catch(NumberFormatException invalid) { throw new Stop("ENDPOINT_INVALID",0); }
        var result=ssh.connect(new ConnectionTarget(endpoint.endpointId(),host,port),
            new ConnectSpec(device.credentialReferenceId(),
                PersistedManagementEndpointTrustResolver.scopeRef(host,port),java.util.Optional.empty()),Duration.ofSeconds(30));
        if(!(result instanceof ConnectResult.Authenticated auth)) throw new Stop("TRUSTED_CONNECTION_REQUIRED",0);
        return new Member(summary.deviceId(),auth.session());
    }
    private GateResolution.Known gate(String command) {
        boolean write=DOWN.equals(command)||UP.equals(command);
        if(!writeAllowed(readiness ? "READINESS" : "FAILOVER", command))
            throw new Stop("READINESS_WRITE_REFUSED",0);
        String key=vsId==null?command:"bash -lc 'vsenv <VSID> && "+command+"'";
        var resolution=GateResolver.resolve(new CanonicalCommandKey("check_point","cp_gaia_gateway",
            "expert","SSH_EXEC",key),java.util.Optional.of(write
                ?ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE:ActionClass.CLASS_0_READ),gates);
        if(!(resolution instanceof GateResolution.Known known)) throw new Stop("COMMAND_GATE_UNAVAILABLE",0);
        String expected=switch(command) {
            case STAT -> vsId==null?"cp_inventory_cphaprob_stat":"cp_inventory_vsid_cphaprob_stat";
            case IF -> vsId==null?"cp_inventory_cphaprob_a_if":"cp_inventory_vsid_cphaprob_a_if";
            case TABLE -> "cp_failover_tablestat";
            case ARP -> "cp_failover_arp";
            case CONN -> "cp_failover_connections";
            case TRAFFIC -> "cp_failover_traffic";
            case SYNC -> "cp_failover_syncstat";
            case POLICY -> "cp_failover_fw_stat";
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
        if(!attempts.findByJobAndStep(jobId,commandIndex).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
        String attempt=attempts.insertPreContact(jobId,epoch,commandIndex++,g.gateId(),g.actionClass().id(),1);
        if(attempt==null) throw new Stop("PRE_CONTACT_RECORD_FAILED",0);
        if(!attempts.markBoundaryCrossed(attempt,epoch)) throw new Stop("PRE_CONTACT_UNCERTAIN",0);
        store.command(runId,g.gateId());
        // Measured 2026-09-30: on the fleet's gateways a plain exec of 'cphaprob stat' exits 0 with no output -- the
        // exec shell lacks the Check Point environment. Inventory already falls back to a login shell; every CP command
        // here (reads and clusterXL_admin alike) runs in one, as the vsenv-wrapped VS form always did.
        String literal=vsId==null?"bash -lc '"+command+"'":"bash -lc 'vsenv "+vsId+" && "+command+"'";
        if(DOWN.equals(command)||UP.equals(command)) writeInFlight=true;
        ExecResult result=ssh.exec(member.session(),new ExecSpec(literal,IF.equals(command)),Duration.ofSeconds(g.timeoutS()));
        if(!(result instanceof ExecResult.Completed completed) || completed.exitStatus()!=0) {
            attempts.writeOutcome(attempt,epoch,"FAILED","COMMAND_UNAVAILABLE",false,null,null,null);
            throw new Stop("COMMAND_UNAVAILABLE",0);
        }
        if(completed.output()==null || completed.output().length()>262144)
            throw new Stop("OUTPUT_UNAVAILABLE",0);
        if(!attempts.writeOutcome(attempt,epoch,"MATCHED",null,true,null,null,null))
            throw new Stop("ATTEMPT_RECORD_FAILED",0);
        writeInFlight=false;
        int check=switch(command) {
            case STAT -> 1; case TABLE -> 2; case IF -> 3; case ARP -> 5;
            case CONN -> 6; case TRAFFIC -> 8; case SYNC -> 9; case POLICY -> 10;
            case PNOTES -> 11; case BONDS -> 12; case FAILOVER -> 13; case ROUTING -> 14;
            default -> 0;
        };
        if (check!=0) shapes.capture(check,completed.output());
        return check==0?completed.output():normalizeRead(command,completed.output(),vsId,shapes);
    }
    /** Remove only the documented transport preamble; never mask command errors. */
    static String normalizeRead(String command,String output,String vsId,ReadinessShapeLog shapes) {
        if(output==null) return null;
        String normalized=output;
        if(vsId!=null) normalized=normalized.replaceFirst(
            "\\AContext is set to Virtual Device [^\\r\\n]+ \\(ID "+java.util.regex.Pattern.quote(vsId)+"\\)\\.(?:\\r?\\n|$)", "");
        int end=normalized.indexOf('\n');
        if(end>=0 && normalized.substring(0,end).stripTrailing().matches("Warning! [^\\r\\n]*\\.")) {
            String remaining=normalized.substring(end+1);
            if(readable(command,remaining)) {
                shapes.logBannerStripped();
                normalized=remaining;
            }
        }
        return normalized;
    }
    private static boolean readable(String command,String output) {
        return switch(command) {
            case STAT -> !"UNKNOWN".equals(CpFailoverChecks.state(output).mode());
            case TABLE -> !CpFailoverChecks.ipTable(output).isEmpty();
            case IF -> CpFailoverChecks.interfaces(output).ccpPresent();
            case ARP -> !output.isBlank() && CpFailoverChecks.arpCount(output)>=0;
            case CONN -> CpFailoverChecks.connections(output)!=null;
            case TRAFFIC -> !CpFailoverChecks.bytesByInterface(output).isEmpty();
            case SYNC -> !"UNKNOWN".equals(CpFailoverChecks.syncStatus(output));
            case POLICY -> !"UNKNOWN".equals(CpFailoverChecks.policy(output).status());
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
    private Pair checks(String phase,Member first,Member second,Pair before,Member oldActive) throws InterruptedException {
        var a=CpFailoverChecks.state(command(first,STAT));
        var b=CpFailoverChecks.state(command(second,STAT));
        boolean states=before==null?CpFailoverChecks.corroborated(a,b)
            : roles(a,b,oldActive==first?second:first,"ACTIVE",oldActive,"DOWN",first,second);
        String stateStatus=states?"PASS":"HA".equals(a.mode()) && "HA".equals(b.mode())?"FAIL":"UNKNOWN";
        record(phase,first,1,stateStatus,"{\"role\":\""+a.localRole()+"\"}");
        record(phase,second,1,stateStatus,"{\"role\":\""+b.localRole()+"\"}");
        if(!states) checkFailed("CLUSTER_STATE_NOT_READY",1,stateStatus);
        var ta=CpFailoverChecks.ipTable(command(first,TABLE));
        var tb=CpFailoverChecks.ipTable(command(second,TABLE));
        boolean tables=CpFailoverChecks.twoTableMembers(ta) && ta.equals(tb);
        String tableStatus=tables?"PASS":vsId==null && CpFailoverChecks.twoTableMembers(ta)
            && CpFailoverChecks.twoTableMembers(tb)?"FAIL":"UNKNOWN";
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
        int arpA=CpFailoverChecks.arpCount(command(first,ARP));
        int arpB=CpFailoverChecks.arpCount(command(second,ARP));
        Member active=before==null?("ACTIVE".equals(a.localRole())?first:second):(oldActive==first?second:first);
        long baseArp=before==null?(active==first?arpA:arpB):(oldActive==first?before.a().arp():before.b().arp());
        long currentArp=active==first?arpA:arpB;
        boolean arp=arpA>=0 && arpB>=0 && (before==null
            ?CpFailoverChecks.ratio(active==first?arpB:arpA,baseArp,CpFailoverChecks.ARP_MIN_RATIO)
            :CpFailoverChecks.ratio(currentArp,baseArp,CpFailoverChecks.ARP_MIN_RATIO));
        // Contract §15 (PO 2026-09-30): ARP is information only -- counts recorded, never blocking.
        String arpStatus=arpA<0 || arpB<0?"UNKNOWN":"PASS";
        record(phase,first,5,arpStatus,arpA<0?"{}":"{\"count\":"+arpA+"}");
        record(phase,second,5,arpStatus,arpB<0?"{}":"{\"count\":"+arpB+"}");
        var ca=CpFailoverChecks.connections(command(first,CONN));
        var cb=CpFailoverChecks.connections(command(second,CONN));
        long baseConn=ca==null || cb==null?-1:before==null?(active==first?ca.count():cb.count())
            :(oldActive==first?before.a().connections().count():before.b().connections().count());
        long currentConn=ca==null || cb==null?-1:active==first?ca.count():cb.count();
        long comparedConn=before==null?(ca==null || cb==null?-1:active==first?cb.count():ca.count()):currentConn;
        var connection=CpFailoverChecks.connectionParity(baseConn,comparedConn,before!=null);
        String connStatus=before==null && !states?"UNKNOWN":connection.status();
        for(Member member:List.of(first,second)) {
            var counts=member==first?ca:cb;
            var derived=new java.util.HashMap<>(connection.derived());
            if(counts!=null) { derived.put("count",counts.count()); derived.put("peak",counts.peak()); }
            record(phase,member,6,connStatus,json(derived));
        }
        if(!"PASS".equals(connStatus)) checkFailed("CONNECTIONS_BELOW_TOLERANCE",6,connStatus);
        var preA=CpFailoverChecks.bytesByInterface(command(first,TRAFFIC));
        var preB=CpFailoverChecks.bytesByInterface(command(second,TRAFFIC));
        pause.sleep(Duration.ofSeconds(5));
        long rateA=CpFailoverChecks.trafficBytesPerSecond(preA,
            CpFailoverChecks.bytesByInterface(command(first,TRAFFIC)),ia.trafficNames());
        long rateB=CpFailoverChecks.trafficBytesPerSecond(preB,
            CpFailoverChecks.bytesByInterface(command(second,TRAFFIC)),ib.trafficNames());
        long baseRate=before==null?(active==first?rateA:rateB)
            :(oldActive==first?before.a().traffic():before.b().traffic());
        long currentRate=active==first?rateA:rateB;
        boolean traffic=rateA>=0 && rateB>=0 && (before==null || currentRate>0
            && CpFailoverChecks.ratio(currentRate,baseRate,CpFailoverChecks.TRAFFIC_MIN_RATIO));
        String trafficStatus=traffic?"PASS":rateA<0 || rateB<0?"UNKNOWN":"FAIL";
        record(phase,first,8,trafficStatus,rateA<0?"{}":"{\"bytesPerSecond\":"+rateA+"}");
        record(phase,second,8,trafficStatus,rateB<0?"{}":"{\"bytesPerSecond\":"+rateB+"}");
        if(!traffic) checkFailed("TRAFFIC_BELOW_TOLERANCE",8,trafficStatus);
        String syncOutputA=before==null || active==first?command(first,SYNC):null;
        String syncOutputB=before==null || active==second?command(second,SYNC):null;
        String syncA=recordSync(phase,first,syncOutputA);
        String syncB=recordSync(phase,second,syncOutputB);
        if("FAIL".equals(syncA) || "FAIL".equals(syncB) || "UNKNOWN".equals(syncA) || "UNKNOWN".equals(syncB))
            checkFailed("STATE_SYNC_NOT_READY",9,"FAIL".equals(syncA) || "FAIL".equals(syncB)?"FAIL":"UNKNOWN");
        var policyA=CpFailoverChecks.policy(command(first,POLICY));
        var policyB=CpFailoverChecks.policy(command(second,POLICY));
        var parity=CpFailoverChecks.policyParity(policyA,policyB,
            before==null?null:before.a().policyName(),before==null?null:before.b().policyName());
        String policyStatus=parity.status();
        record(phase,first,10,policyStatus,json(parity.derived()));
        record(phase,second,10,policyStatus,json(parity.derived()));
        if(!"PASS".equals(policyStatus)) checkFailed("POLICY_NOT_MATCHED",10,policyStatus);
        for(Member member:List.of(first,second)) {
            // The intentionally down former active reports ADMIN_DOWN after the switch.
            if(before!=null && member!=active) continue;
            var pnotes=CpFailoverChecks.pnotes(command(member,PNOTES));
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
            if(before!=null && member!=active) continue;
            var routing=CpFailoverChecks.routing(command(member,ROUTING));
            if(member==first) routingA=routing; else routingB=routing;
            String status=routing==null?"UNKNOWN":before==null?"PASS"
                :routing.defaultRoute() && routing.count()==before.forMember(oldActive,first).routing().count()?"PASS":"FAIL";
            record(phase,member,14,status,routing==null?"{}":json(Map.of(
                "routeCount",routing.count(),"defaultRoute",routing.defaultRoute())));
            if(!"PASS".equals(status)) checkFailed("ROUTING_NOT_READY",14,status);
        }
        return new Pair(new Measure(a,ta,ia,arpA,ca,rateA,policyA.name(),routingA),
            new Measure(b,tb,ib,arpB,cb,rateB,policyB.name(),routingB));
    }
    private void checkFailed(String code,int check,String status) {
        if (!readiness) throw new Stop(code,check,status);
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(JsonProcessingException invalid) { throw new IllegalStateException("Derived projection unavailable"); }
    }
    private void record(String phase,Member member,int no,String status,String derived) {
        if (readiness && no!=13 && !"PASS".equals(status)
                && (readinessFailure==null || !"FAIL".equals(readinessFailure.status) && "FAIL".equals(status)))
            readinessFailure=new Stop("CHECK_NOT_READY",no,status);
        shapes.logUnknown(no,status);
        store.check(runId,phase,member.id(),vsId,no,status,derived);
    }
    private boolean waitFor(Member first,Member second,Member active,String activeRole,Member other,
            String otherRole) throws InterruptedException {
        for(int i=0;i<MAX_POLLS;i++) {
            var a=CpFailoverChecks.state(command(first,STAT));
            var b=CpFailoverChecks.state(command(second,STAT));
            if(roles(a,b,active,activeRole,other,otherRole,first,second)) return true;
            if(i<MAX_POLLS-1) pause.sleep(POLL_INTERVAL);
        }
        return false;
    }
    private static boolean roles(CpFailoverChecks.State a,CpFailoverChecks.State b,Member active,
            String activeRole,Member other,String otherRole,Member first,Member second) {
        return "HA".equals(a.mode()) && "HA".equals(b.mode()) && a.members().size()==2
            && a.members().equals(b.members()) && a.localId()!=null && b.localId()!=null
            && !a.localId().equals(b.localId())
            && (active==first?activeRole:otherRole).equals(a.localRole())
            && (active==second?activeRole:otherRole).equals(b.localRole());
    }
}
