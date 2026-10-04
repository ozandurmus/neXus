package com.securityexpert.nexus.ui2.worker.diagnostic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
class DiagnosticJobExecutorTest {
    @Test void gatewayPlainCommandsAreWrapped() {
        assertShell("gateway",null,"cp_configuration_show_version_all","clish -c 'show version all'",true,"bash -lc 'clish -c '\"'\"'show version all'\"'\"''");
        assertShell("gateway",null,"cp_failover_syncstat","cphaprob syncstat",true,"bash -lc 'cphaprob syncstat'");
    }

    @Test void alreadyWrappedGatewayCommandIsUntouched() {
        assertShell("gateway",null,"cp_failover_syncstat_vsid","bash -lc 'vsenv 001 && cphaprob syncstat'",true,
            "bash -lc 'vsenv 001 && cphaprob syncstat'");
    }

    @Test void managementScopeCommandIsNotWrapped() {
        assertShell("management_server",null,"mds_show_version_all","clish -c 'show version all'",true,
            "clish -c 'show version all'");
    }

    @Test void gaiaEmbeddedStaysInteractive() {
        assertShell("gateway",null,"cp_spark_backup_log","show backup-settings-log",false,"show backup-settings-log");
        assertShell("gateway","1550","cp_spark_backup_log","show backup-settings-log",false,"show backup-settings-log");
    }

    private void assertShell(String role,String model,String gateId,String command,boolean expert,String expectedCommand) {
        var leases=stub(JobLeaseRepository.class,(method,args)->true);
        var attempts=stub(JobStepAttemptRepository.class,(method,args)->switch(method) {
            case "findByJobAndStep" -> List.of();
            case "insertPreContact" -> "attempt-1";
            case "markBoundaryCrossed" -> true;
            default -> null;
        });
        var device=new DeviceRecord("device-1",role,"check_point","manual",Instant.now(),false,
            DeviceEnrollmentState.ENROLLED,false,"credential-ref");
        var summary=mock(DeviceSummaryRecord.class);
        when(summary.observedModel()).thenReturn(Optional.ofNullable(model));
        var devices=stub(DeviceRepository.class,(method,args)->switch(method) {
            case "find" -> Optional.of(device);
            case "findSummary" -> Optional.of(summary);
            case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord("endpoint-1","device-1","ssh_exec","192.0.2.10",Instant.now()));
            default -> Optional.empty();
        });
        var job=new JobRecordDao.DiagnosticJob("job-1","device-1",null,"CLAIMED",null,false,null,null,null,
            command,"actor",Instant.now(),null,gateId);
        var jobs=stub(JobRecordDao.class,(method,args)->Optional.of(job));
        var transport=mock(DeviceTransport.class);
        when(transport.connect(any(),any(),any())).thenReturn(new ConnectResult.Authenticated(mock(TransportSession.class)));
        when(transport.exec(any(),any(),any())).thenReturn(new ExecResult.ChannelFailed("synthetic"));
        when(transport.execInteractive(any(),any(),any())).thenReturn(new ExecResult.ChannelFailed("synthetic"));
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        new DiagnosticJobExecutor(leases,attempts,devices,jobs,transport,
            key -> rows.stream().filter(r -> r.key().equals(key)).toList(),mock(ArtefactStore.class))
            .execute("job-1",1,"device-1","192.0.2.10",22,"credential-ref");
        if (expert) {
            verify(transport).exec(any(),eq(new ExecSpec(expectedCommand,true)),any());
            verify(transport,never()).execInteractive(any(),any(),any());
        } else {
            verify(transport).execInteractive(any(),eq(new ExecSpec(expectedCommand,true)),any());
            verify(transport,never()).exec(any(),any(),any());
        }
    }
    @Test void priorAttemptNeverContactsDevice() {
        var terminal=new java.util.concurrent.atomic.AtomicReference<JobState>();
        var contacts=new java.util.concurrent.atomic.AtomicInteger();
        var leases=stub(JobLeaseRepository.class,(method,args)-> {
            if (method.equals("transitionState")) terminal.set((JobState)args[3]);
            return true;
        });
        var attempts=stub(JobStepAttemptRepository.class,(method,args)->List.of(
            new StepAttempt("attempt-1","job-1",0,0,1,"diagnostic","read",true,Optional.empty(),Optional.empty())));
        var device=new DeviceRecord("device-1","gateway","fortinet","manual",Instant.now(),false,DeviceEnrollmentState.ENROLLED,false,"credential-ref");
        var devices=stub(DeviceRepository.class,(method,args)->switch(method) {
            case "find" -> Optional.of(device);
            case "findSummary" -> Optional.empty();
            case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord("endpoint-1","device-1","ssh_exec","192.0.2.10",Instant.now()));
            default -> Optional.empty();
        });
        var job=new JobRecordDao.DiagnosticJob("job-1","device-1",null,"CLAIMED",null,false,null,null,null,
            "get system status","actor",Instant.now(),null);
        var jobs=stub(JobRecordDao.class,(method,args)->Optional.of(job));
        var ssh=stub(DeviceTransport.class,(method,args)-> {contacts.incrementAndGet(); return null;});
        var store=stub(ArtefactStore.class,(method,args)-> {contacts.incrementAndGet(); return null;});
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var executor=new DiagnosticJobExecutor(leases,attempts,devices,jobs,ssh,
            key -> rows.stream().filter(r -> r.key().equals(key)).toList(),store);
        executor.execute("job-1",1,"device-1","192.0.2.10",22,"credential-ref");
        assertEquals(JobState.OUTCOME_UNKNOWN,terminal.get());
        assertEquals(0,contacts.get());
    }
    @Test void revokedReadAndWriteGatesAreRejectedBeforeContact() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        for (String gateId : new String[]{"cp_inventory_cphaprob_stat", "cp_failover_down", "cp_failover_up"}) {
            var leases = mock(JobLeaseRepository.class);
            var attempts = mock(JobStepAttemptRepository.class);
            var devices = mock(DeviceRepository.class);
            var jobs = mock(JobRecordDao.class);
            var transport = mock(DeviceTransport.class);
            var store = mock(ArtefactStore.class);
            when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", "gateway", "check_point",
                    "manual", Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
            when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(new EndpointRecord(
                    "endpoint-1", "device-1", "ssh_exec", "192.0.2.10", Instant.now())));
            String command = gateId.equals("cp_inventory_cphaprob_stat") ? "cphaprob stat"
                    : gateId.equals("cp_failover_down") ? "clusterXL_admin down" : "clusterXL_admin up";
            when(jobs.findDiagnostic("job-1")).thenReturn(Optional.of(new JobRecordDao.DiagnosticJob(
                    "job-1", "device-1", null, "CLAIMED", null, false, null, null, null,
                    command, "synthetic-actor", Instant.now(), null, gateId)));
            new DiagnosticJobExecutor(leases, attempts, devices, jobs, transport,
                    key -> gateId.equals("cp_inventory_cphaprob_stat") ? List.of()
                            : rows.stream().filter(row -> row.key().equals(key)).toList(), store)
                    .execute("job-1", 1, "device-1", "192.0.2.10", 22, "credential-ref");
            verify(leases).transitionState("job-1", 1, JobState.CLAIMED, JobState.REJECTED,
                    "system:worker", "diagnostic_claim_check", "DIAGNOSTIC_UNAVAILABLE");
            verifyNoInteractions(transport, store, attempts);
        }
    }
    private static <T> T stub(Class<T> type,java.util.function.BiFunction<String,Object[],Object> handler) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
            (proxy,method,args)->handler.apply(method.getName(),args)));
    }
}
