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
    public DiagnosticJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            JobRecordDao jobs, DeviceTransport ssh, GateRegistryPort gates, ArtefactStore store) {
        this.leases=leases; this.attempts=attempts; this.devices=devices; this.jobs=jobs; this.ssh=ssh; this.gates=gates; this.store=store;
    }
    public void execute(String jobId, long epoch, String deviceId, String host, int port, String credentialRef) {
        var device=devices.find(deviceId);
        var job=jobs.findDiagnostic(jobId);
        var read=device.flatMap(d -> job.flatMap(j -> DiagnosticRead.resolve(d.vendorHint(),d.role(),j.command(),gates)));
        if (device.isEmpty() || !device.get().permitsReadCollection() || job.isEmpty()
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
            var result=ssh.execInteractive(session,new ExecSpec(read.get().command(),true),Duration.ofSeconds(read.get().timeoutSeconds()));
            if (!(result instanceof ExecResult.Completed completed)) { finish(jobId,epoch,JobState.OUTCOME_UNKNOWN,"OUTPUT_UNAVAILABLE"); return; }
            String output=DiagnosticText.scrubSecrets(completed.output());
            byte[] bytes=output.getBytes(StandardCharsets.UTF_8);
            if (bytes.length>DiagnosticText.MAX_BYTES) {
                output=new String(java.util.Arrays.copyOf(bytes,DiagnosticText.MAX_BYTES),StandardCharsets.UTF_8)+"\n[TRUNCATED]";
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
            finish(jobId,epoch,JobState.OUTCOME_UNKNOWN,"DIAGNOSTIC_OUTCOME_UNCERTAIN");
        } finally { if (session!=null) ssh.disconnect(session); }
    }
    private void finish(String id,long epoch,JobState state,String reason) {
        leases.transitionState(id,epoch,JobState.EXECUTING,state,"system:worker","diagnostic_finished",reason);
    }
}
