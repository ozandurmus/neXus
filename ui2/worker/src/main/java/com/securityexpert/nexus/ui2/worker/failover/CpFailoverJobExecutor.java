package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final String DOWN="clusterXL_admin down", UP="clusterXL_admin up";
    private final JooqCpFailoverRepository store;
    private final DeviceRepository devices;
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceTransport ssh;
    private final GateRegistryPort gates;
    private final Pause pause;
    private String jobId,runId,vsId;
    private long epoch;
    private int commandIndex;
    private boolean wrote;
    private boolean writeInFlight;

    private record Member(String id, TransportSession session) {}
    private record Measure(CpFailoverChecks.State state, Set<String> table,
            CpFailoverChecks.Interfaces interfaces, int arp, CpFailoverChecks.Connections connections, long traffic,
            String policyName) {}
    private record Pair(Measure a, Measure b) {
        Measure forMember(Member member,Member first) { return member==first?a:b; }
    }
    private static final class Stop extends RuntimeException {
        final String code; final int check;
        Stop(String code,int check) { super(code); this.code=code; this.check=check; }
    }

    public CpFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices,
            JobLeaseRepository leases, JobStepAttemptRepository attempts,DeviceTransport ssh,
            GateRegistryPort gates) {
        this(store,devices,leases,attempts,ssh,gates,d -> Thread.sleep(d.toMillis()));
    }
    public CpFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices,
            JobLeaseRepository leases, JobStepAttemptRepository attempts,DeviceTransport ssh,
            GateRegistryPort gates,Pause pause) {
        this.store=store; this.devices=devices; this.leases=leases; this.attempts=attempts;
        this.ssh=ssh; this.gates=gates; this.pause=pause;
    }

    public void execute(String jobId,long epoch) {
        var run=store.runByJob(jobId);
        if(run.isEmpty()) {
            leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.FAILED,"system:cp-failover-worker",
                "cp_failover_missing_run","RUN_NOT_FOUND"); return;
        }
        this.jobId=jobId; this.runId=run.get().id(); this.vsId=run.get().vsId();
        this.epoch=epoch; this.commandIndex=0; this.wrote=false; this.writeInFlight=false;
        if(!leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.EXECUTING,
                "system:cp-failover-worker","cp_failover_start")) return;
        Member first=null,second=null;
        try {
            if(!store.windowValid(runId)) throw new Stop("WINDOW_EXPIRED",0);
            List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(run.get().clusterRef());
            if(members.size()!=2 || members.stream().anyMatch(m -> !"check_point".equals(m.vendorHint())))
                throw new Stop("CLUSTER_NOT_ELIGIBLE",0);
            if(!attempts.findByJobAndStep(jobId,0).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
            for(String command:List.of(STAT,TABLE,IF,ARP,CONN,TRAFFIC,SYNC,POLICY,DOWN,UP)) gate(command);
            first=connect(members.get(0)); second=connect(members.get(1));
            store.state(runId,"PRECHECK","PRECHECK",null,null,null);
            Pair before=checks("pre",first,second,null,null);
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
            stop(stopped.code,stopped.check);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); stop("INTERRUPTED",0);
        } catch (RuntimeException unexpected) {
            stop(wrote?"OUTCOME_UNCERTAIN":"PRECHECK_UNAVAILABLE",0);
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
        String key=vsId==null?command:"bash -lc 'vsenv <VSID> && "+command+"'";
        var resolution=GateResolver.resolve(new CanonicalCommandKey("check_point","cp_gaia_gateway",
            "expert","SSH_EXEC",key),java.util.Optional.of(write
                ?ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE:ActionClass.CLASS_0_READ),gates);
        if(!(resolution instanceof GateResolution.Known known)) throw new Stop("COMMAND_GATE_UNAVAILABLE",0);
        String expected=switch(command) {
            case STAT -> vsId==null?"cp_inventory_cphaprob_stat":"cp_inventory_vsid_cphaprob_stat";
            case IF -> vsId==null?"cp_inventory_cphaprob_a_m_if":"cp_inventory_vsid_cphaprob_a_m_if";
            case TABLE -> "cp_failover_tablestat";
            case ARP -> "cp_failover_arp";
            case CONN -> "cp_failover_connections";
            case TRAFFIC -> "cp_failover_traffic";
            case SYNC -> "cp_failover_syncstat";
            case POLICY -> "cp_failover_fw_stat";
            case DOWN -> "cp_failover_down";
            case UP -> "cp_failover_up";
            default -> throw new Stop("COMMAND_NOT_APPROVED",0);
        }+(vsId!=null && !STAT.equals(command) && !IF.equals(command)?"_vsid":"");
        if(!expected.equals(known.gateId())) throw new Stop("COMMAND_GATE_MISMATCH",0);
        return known;
    }
    private String command(Member member,String command) {
        var g=gate(command);
        if(vsId!=null && !vsId.matches("[0-9]{1,10}")) throw new Stop("VSID_INVALID",0);
        if(!attempts.findByJobAndStep(jobId,commandIndex).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED",0);
        String attempt=attempts.insertPreContact(jobId,epoch,commandIndex++,g.gateId(),g.actionClass().id(),1);
        if(attempt==null) throw new Stop("PRE_CONTACT_RECORD_FAILED",0);
        if(!attempts.markBoundaryCrossed(attempt,epoch)) throw new Stop("PRE_CONTACT_UNCERTAIN",0);
        store.command(runId,g.gateId());
        String literal=vsId==null?command:"bash -lc 'vsenv "+vsId+" && "+command+"'";
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
        return completed.output();
    }
    private Pair checks(String phase,Member first,Member second,Pair before,Member oldActive) throws InterruptedException {
        var a=CpFailoverChecks.state(command(first,STAT));
        var b=CpFailoverChecks.state(command(second,STAT));
        boolean states=before==null?CpFailoverChecks.corroborated(a,b)
            : roles(a,b,oldActive==first?second:first,"ACTIVE",oldActive,"DOWN",first,second);
        String stateStatus=states?"PASS":"HA".equals(a.mode()) && "HA".equals(b.mode())?"FAIL":"UNKNOWN";
        record(phase,first,1,stateStatus,"{\"role\":\""+a.localRole()+"\"}");
        record(phase,second,1,stateStatus,"{\"role\":\""+b.localRole()+"\"}");
        if(!states) throw new Stop("CLUSTER_STATE_NOT_READY",1);
        var ta=CpFailoverChecks.ipTable(command(first,TABLE));
        var tb=CpFailoverChecks.ipTable(command(second,TABLE));
        boolean tables=CpFailoverChecks.twoTableMembers(ta) && ta.equals(tb);
        String tableStatus=tables?"PASS":CpFailoverChecks.twoTableMembers(ta)
            && CpFailoverChecks.twoTableMembers(tb)?"FAIL":"UNKNOWN";
        record(phase,first,2,tableStatus,"{\"entries\":"+ta.size()+"}");
        record(phase,second,2,tableStatus,"{\"entries\":"+tb.size()+"}");
        if(!tables) throw new Stop("CLUSTER_IP_TABLE_MISMATCH",2);
        var ia=CpFailoverChecks.interfaces(command(first,IF));
        var ib=CpFailoverChecks.interfaces(command(second,IF));
        record(phase,first,3,ia.healthy()?"PASS":ia.ccpPresent()?"FAIL":"UNKNOWN","{\"up\":"+ia.names().size()+"}");
        record(phase,second,3,ib.healthy()?"PASS":ib.ccpPresent()?"FAIL":"UNKNOWN","{\"up\":"+ib.names().size()+"}");
        if(!ia.healthy() || !ib.healthy()) throw new Stop("INTERFACE_NOT_READY",3);
        int arpA=CpFailoverChecks.arpCount(command(first,ARP));
        int arpB=CpFailoverChecks.arpCount(command(second,ARP));
        Member active=before==null?("ACTIVE".equals(a.localRole())?first:second):(oldActive==first?second:first);
        long baseArp=before==null?(active==first?arpA:arpB):(oldActive==first?before.a().arp():before.b().arp());
        long currentArp=active==first?arpA:arpB;
        boolean arp=arpA>=0 && arpB>=0 && (before==null
            ?CpFailoverChecks.ratio(active==first?arpB:arpA,baseArp,CpFailoverChecks.ARP_MIN_RATIO)
            :CpFailoverChecks.ratio(currentArp,baseArp,CpFailoverChecks.ARP_MIN_RATIO));
        String arpStatus=arp?"PASS":arpA<0 || arpB<0?"UNKNOWN":"FAIL";
        record(phase,first,5,arpStatus,arpA<0?"{}":"{\"count\":"+arpA+"}");
        record(phase,second,5,arpStatus,arpB<0?"{}":"{\"count\":"+arpB+"}");
        if(!arp) throw new Stop("ARP_BELOW_TOLERANCE",5);
        var ca=CpFailoverChecks.connections(command(first,CONN));
        var cb=CpFailoverChecks.connections(command(second,CONN));
        long baseConn=ca==null || cb==null?-1:before==null?(active==first?ca.count():cb.count())
            :(oldActive==first?before.a().connections().count():before.b().connections().count());
        long currentConn=ca==null || cb==null?-1:active==first?ca.count():cb.count();
        boolean conn=ca!=null && cb!=null && (before==null
            ?CpFailoverChecks.ratio(active==first?cb.count():ca.count(),baseConn,CpFailoverChecks.CONNECTION_MIN_RATIO)
            :CpFailoverChecks.ratio(currentConn,baseConn,CpFailoverChecks.CONNECTION_MIN_RATIO));
        String connStatus=conn?"PASS":ca==null || cb==null?"UNKNOWN":"FAIL";
        record(phase,first,6,connStatus,ca==null?"{}":"{\"count\":"+ca.count()+",\"peak\":"+ca.peak()+"}");
        record(phase,second,6,connStatus,cb==null?"{}":"{\"count\":"+cb.count()+",\"peak\":"+cb.peak()+"}");
        if(!conn) throw new Stop("CONNECTIONS_BELOW_TOLERANCE",6);
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
        if(!traffic) throw new Stop("TRAFFIC_BELOW_TOLERANCE",8);
        String syncA=before==null || active==first?CpFailoverChecks.syncStatus(command(first,SYNC)):null;
        String syncB=before==null || active==second?CpFailoverChecks.syncStatus(command(second,SYNC)):null;
        if(syncA!=null) record(phase,first,9,syncA,"{}");
        if(syncB!=null) record(phase,second,9,syncB,"{}");
        if("FAIL".equals(syncA) || "FAIL".equals(syncB) || "UNKNOWN".equals(syncA) || "UNKNOWN".equals(syncB))
            throw new Stop("STATE_SYNC_NOT_READY",9);
        var policyA=CpFailoverChecks.policy(command(first,POLICY));
        var policyB=CpFailoverChecks.policy(command(second,POLICY));
        String policyStatus="FAIL".equals(policyA.status()) || "FAIL".equals(policyB.status())?"FAIL"
            :"UNKNOWN".equals(policyA.status()) || "UNKNOWN".equals(policyB.status())?"UNKNOWN"
            :!policyA.name().equals(policyB.name()) || before!=null &&
                (!policyA.name().equals(before.a().policyName()) || !policyB.name().equals(before.b().policyName()))?"FAIL":"PASS";
        record(phase,first,10,policyStatus,policyA.installedAt()==null?"{}":"{\"installedAt\":\""+policyA.installedAt()+"\"}");
        record(phase,second,10,policyStatus,policyB.installedAt()==null?"{}":"{\"installedAt\":\""+policyB.installedAt()+"\"}");
        if(!"PASS".equals(policyStatus)) throw new Stop("POLICY_NOT_MATCHED",10);
        return new Pair(new Measure(a,ta,ia,arpA,ca,rateA,policyA.name()),
            new Measure(b,tb,ib,arpB,cb,rateB,policyB.name()));
    }
    private void record(String phase,Member member,int no,String status,String derived) {
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
