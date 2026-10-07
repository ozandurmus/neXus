package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.platform.DiagnosticText;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/** One reviewed read through the existing trusted transport. Never retries a recorded attempt. */
public final class DiagnosticJobExecutor {
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceRepository devices;
    private final JobRecordDao jobs;
    private final DeviceTransport ssh;
    private final GateRegistryPort gates;
    private final ArtefactStore store;
    private final com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository platformFacts;
    private final com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository inventory;
    public DiagnosticJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            JobRecordDao jobs, DeviceTransport ssh, GateRegistryPort gates, ArtefactStore store) {
        this(leases, attempts, devices, jobs, ssh, gates, store,
                com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository.NONE);
    }
    public DiagnosticJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            JobRecordDao jobs, DeviceTransport ssh, GateRegistryPort gates, ArtefactStore store,
            com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository platformFacts) {
        this(leases, attempts, devices, jobs, ssh, gates, store, platformFacts, null);
    }
    public DiagnosticJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            JobRecordDao jobs, DeviceTransport ssh, GateRegistryPort gates, ArtefactStore store,
            com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository platformFacts,
            com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository inventory) {
        this.leases=leases; this.attempts=attempts; this.devices=devices; this.jobs=jobs; this.ssh=ssh; this.gates=gates; this.store=store;
        this.platformFacts=platformFacts;
        this.inventory = inventory;
    }
    public void execute(String jobId, long epoch, String deviceId, String host, int port, String credentialRef) {
        var device=devices.find(deviceId);
        var job=jobs.findDiagnostic(jobId);
        String model=platformFacts.find(deviceId).flatMap(f -> f.platformFamily()).filter("gaia_embedded"::equals)
            .orElseGet(() -> devices.findSummary(deviceId).flatMap(s -> s.observedModel()).orElse(null));
        var read=device.flatMap(d -> job.flatMap(j -> j.gateId()==null
            ? DiagnosticRead.resolve(d.vendorHint(),d.role(),model,j.command(),gates)
            : DiagnosticRead.resolveStored(d.vendorHint(),d.role(),model,j.gateId(),j.command(),gates)));
        boolean cpview = read.filter(r -> DiagnosticRead.CPVIEW_GATE.equals(r.gateId())).isPresent();
        if (cpview && (inventory == null || device.isEmpty()
                || !com.securityexpert.nexus.ui2.persistence.device.CpviewTarget.eligible(device.get(),
                    devices.findSummary(deviceId), inventory.findLatestRun(deviceId)))) read = Optional.empty();
        if (device.isEmpty() || !device.get().permitsReadCollection() || job.isEmpty()
                || devices.findEndpointByDeviceId(deviceId).filter(e -> "ssh_exec".equals(e.transportKind())).isEmpty()
                || !deviceId.equals(job.get().targetDeviceId()) || read.isEmpty() || store==null) {
            leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.REJECTED,"system:worker","diagnostic_claim_check","DIAGNOSTIC_UNAVAILABLE");
            return;
        }
        if (!leases.transitionState(jobId,epoch,JobState.CLAIMED,JobState.EXECUTING,"system:worker","diagnostic_start")) return;
        if (!attempts.findByJobAndStep(jobId,0).isEmpty()) { finish(jobId,epoch,JobState.OUTCOME_UNKNOWN,"PRIOR_ATTEMPT_NOT_REPLAYED"); return; }
        String attempt=attempts.insertPreContact(jobId,epoch,0,read.get().gateId(),"read",1);
        if (attempt==null || !attempts.markBoundaryCrossed(attempt,epoch)) { finish(jobId,epoch,JobState.OUTCOME_UNKNOWN,"PRE_CONTACT_UNCERTAIN"); return; }
        TransportSession session=null;
        try {
            var connected=ssh.connect(new ConnectionTarget(UUID.randomUUID().toString(),host,port),
                new ConnectSpec(credentialRef,PersistedManagementEndpointTrustResolver.scopeRef(host,port),Optional.empty()),Duration.ofSeconds(30));
            if (!(connected instanceof ConnectResult.Authenticated authenticated)) {
                finish(jobId,epoch,JobState.FAILED,"CONNECTION_FAILED"); return;
            }
            session=authenticated.session();
            boolean interactive=!"check_point".equals(device.get().vendorHint())
                || "gaia_embedded".equals(read.get().platformRoleScope());
            String command=read.get().command();
            if (!interactive && "cp_gaia_gateway".equals(read.get().platformRoleScope())
                    && !command.startsWith("bash -lc "))
                command="bash -lc '"+command.replace("'", "'\"'\"'")+"'";
            var spec=cpview ? new ExecSpec(command,false,0,com.securityexpert.nexus.ui2.platform.CpviewProjection.MAX_BYTES)
                : new ExecSpec(command,true);
            var result=interactive ? ssh.execInteractive(session,spec,Duration.ofSeconds(read.get().timeoutSeconds()))
                : ssh.exec(session,spec,Duration.ofSeconds(read.get().timeoutSeconds()));
            if (cpview && (result instanceof ExecResult.TimedOut || result instanceof ExecResult.ChannelFailed)) {
                String reason = result instanceof ExecResult.TimedOut ? "TIMEOUT"
                    : "OUTPUT_LIMIT_EXCEEDED".equals(((ExecResult.ChannelFailed) result).reason()) ? "OUTPUT_LIMIT_EXCEEDED" : "OUTPUT_UNAVAILABLE";
                attempts.writeOutcome(attempt,epoch,"EXPECTATION_UNMET",reason,false,0L,0L,null);
                finish(jobId,epoch,JobState.FAILED,reason); return;
            }
            if (!(result instanceof ExecResult.Completed completed)) { finish(jobId,epoch,JobState.OUTCOME_UNKNOWN,"OUTPUT_UNAVAILABLE"); return; }
            if (cpview && completed.output().getBytes(StandardCharsets.UTF_8).length > com.securityexpert.nexus.ui2.platform.CpviewProjection.MAX_BYTES) {
                attempts.writeOutcome(attempt,epoch,"EXPECTATION_UNMET","OUTPUT_LIMIT_EXCEEDED",false,0L,0L,null);
                finish(jobId,epoch,JobState.FAILED,"OUTPUT_LIMIT_EXCEEDED"); return;
            }
            String output=cpview ? com.securityexpert.nexus.ui2.platform.CpviewProjection.project(completed.output())
                : DiagnosticText.scrubSecrets(completed.output());
            byte[] bytes=output.getBytes(StandardCharsets.UTF_8);
            if (bytes.length>DiagnosticText.MAX_BYTES) {
                output=new String(bytes,0,DiagnosticText.MAX_BYTES-32,StandardCharsets.UTF_8)+"\n[TRUNCATED]";
                bytes=output.getBytes(StandardCharsets.UTF_8);
            }
            try (var handle=store.open(deviceId,jobId,device.get().vendorHint(),false)) {
                handle.sink().write(bytes);
                var metadata=handle.finish();
                if (!jobs.writeDiagnosticOutput(jobId,metadata.ref().value(),metadata.wrappedDataKey(),completed.exitStatus(),output.split("\\R",-1).length)) {
                    finish(jobId,epoch,JobState.OUTCOME_UNKNOWN,"OUTPUT_PERSISTENCE_FAILED"); return;
                }
            }
            boolean ok=completed.exitStatus()==0;
            if (!attempts.writeOutcome(attempt,epoch,ok?"MATCHED":"EXPECTATION_UNMET",ok?null:"COMMAND_EXIT_NONZERO",ok,null,(long)bytes.length,null)) {
                finish(jobId,epoch,JobState.OUTCOME_UNKNOWN,"ATTEMPT_RECORD_FAILED"); return;
            }
            finish(jobId,epoch,ok?JobState.COMPLETED:JobState.FAILED,ok?"OUTPUT_RECORDED":"COMMAND_EXIT_NONZERO");
        } catch (Exception failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            finish(jobId,epoch,JobState.OUTCOME_UNKNOWN,"DIAGNOSTIC_OUTCOME_UNCERTAIN");
        } finally { if (session!=null) ssh.disconnect(session); }
    }
    private void finish(String id,long epoch,JobState state,String reason) {
        leases.transitionState(id,epoch,JobState.EXECUTING,state,"system:worker","diagnostic_finished",reason);
    }
}
