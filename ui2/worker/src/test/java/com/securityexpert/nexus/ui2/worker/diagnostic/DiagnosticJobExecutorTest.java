package com.securityexpert.nexus.ui2.worker.diagnostic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
class DiagnosticJobExecutorTest {
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
        var devices=stub(DeviceRepository.class,(method,args)->Optional.of(device));
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
    private static <T> T stub(Class<T> type,java.util.function.BiFunction<String,Object[],Object> handler) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
            (proxy,method,args)->handler.apply(method.getName(),args)));
    }
}
