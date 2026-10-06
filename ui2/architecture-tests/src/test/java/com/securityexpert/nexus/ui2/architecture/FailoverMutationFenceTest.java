package com.securityexpert.nexus.ui2.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.jobs.failover.execution.*;
import com.securityexpert.nexus.ui2.service.api.FailoverExecutionController;
import com.securityexpert.nexus.ui2.service.api.FailoverScheduleController;
import com.securityexpert.nexus.ui2.service.failover.FailoverExecutionService;
import com.securityexpert.nexus.ui2.service.failover.FailoverScheduleService;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.worker.failover.CpFailoverJobExecutor;
import com.securityexpert.nexus.ui2.worker.failover.PanFailoverJobExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pilot fences apply independently of controller routing and of transport registration. */
class FailoverMutationFenceTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes = new ClassFileImporter().importClasses(
        FailoverExecutionController.class, FailoverScheduleController.class,
        FailoverExecutionService.class, FailoverScheduleService.class, CpFailoverService.class,
        CpFailoverJobExecutor.class, PanFailoverJobExecutor.class);

    private void mustCall(Class<?> owner, String method, Class<?> target, String targetMethod) {
        JavaClass type=classes.get(owner);
        assertTrue(type.getMethods().stream().filter(m -> m.getName().equals(method))
            .anyMatch(m -> m.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTarget().getOwner().getName().equals(target.getName())
                    && call.getTarget().getName().equals(targetMethod))), owner.getSimpleName()+"#"+method);
    }

    @Test void genericDispatchRequiresUnconditionalDenialRegardlessOfSwitchValue() {
        for (String value:new String[]{null,"false","true"}) {
            FailoverMutationSwitch.fromValue(value);
            assertEquals(FailoverMutationSwitch.GENERIC_DISABLED,
                assertThrows(SecurityException.class,FailoverMutationSwitch::refuseGenericExecution).getMessage());
        }
        mustCall(FailoverExecutionService.class,"executeFailover",FailoverMutationSwitch.class,"refuseGenericExecution");
        mustCall(FailoverExecutionService.class,"executeScheduledFailover",FailoverMutationSwitch.class,"refuseGenericExecution");
        mustCall(FailoverScheduleService.class,"dispatchScheduledExecution",FailoverMutationSwitch.class,"refuseGenericExecution");
    }

    @Test void disabledControllerPathsCannotCallDispatchOrBookingServices() {
        for (Class<?> controller:List.of(FailoverExecutionController.class,FailoverScheduleController.class)) {
            for (var method:classes.get(controller).getMethods()) {
                if (!List.of("executeFailover","createSchedule","triggerScheduleExecution").contains(method.getName())) continue;
                assertTrue(method.getMethodCallsFromSelf().stream().noneMatch(call ->
                    call.getTarget().getOwner().getName().equals(FailoverExecutionService.class.getName())
                        || call.getTarget().getOwner().getName().equals(FailoverScheduleService.class.getName())));
            }
        }
    }

    @Test void serviceAndEachWorkerCheckTheSameSwitchIncludingBothWriteBoundaries() {
        mustCall(CpFailoverService.class,"request",FailoverMutationSwitch.class,"enabled");
        mustCall(CpFailoverService.class,"startDue",FailoverMutationSwitch.class,"enabled");
        for (Class<?> worker:List.of(CpFailoverJobExecutor.class,PanFailoverJobExecutor.class)) {
            mustCall(worker,"execute",FailoverMutationSwitch.class,"enabled");
            mustCall(worker,"gate",FailoverMutationSwitch.class,"enabled");
        }
        mustCall(CpFailoverJobExecutor.class,"command",FailoverMutationSwitch.class,"enabled");
        mustCall(PanFailoverJobExecutor.class,"call",FailoverMutationSwitch.class,"enabled");
    }

    @Test void bothWorkersUseCommonDurableAdmissionBeforeContactAndAtEachWrite() {
        var repository=com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository.class;
        mustCall(CpFailoverService.class,"request",repository,"requestBound");
        for (Class<?> worker:List.of(CpFailoverJobExecutor.class,PanFailoverJobExecutor.class)) {
            mustCall(worker,"execute",worker,"requireMutationAdmission");
            mustCall(worker,"requireMutationAdmission",repository,"mutationAdmission");
        }
        mustCall(CpFailoverJobExecutor.class,"command",CpFailoverJobExecutor.class,"requireMutationAdmission");
        mustCall(PanFailoverJobExecutor.class,"call",PanFailoverJobExecutor.class,"requireMutationAdmission");
        for (Class<?> worker:List.of(CpFailoverJobExecutor.class,PanFailoverJobExecutor.class)) {
            String send=worker==CpFailoverJobExecutor.class?"command":"call";
            mustCall(worker,send,repository,"prepareDispatch");
            mustCall(worker,send,repository,"dispatch");
            mustCall(worker,"confirmDispatch",repository,"confirmDispatch");
            mustCall(worker,"state",repository,"workerState");
            mustCall(worker,"stop",repository,"workerState");
        }
    }

    @Test void disabledWorkersRefuseEveryWriteEvenWhenPrivateDispatchIsCalledDirectly() throws Exception {
        var disabled=new FailoverMutationSwitch(false);
        var cp=new CpFailoverJobExecutor(null,null,null,null,null,null,d -> {},java.time.Duration.ZERO,disabled);
        var pan=new PanFailoverJobExecutor(null,null,null,null,null,null,null,d -> {},java.time.Duration.ZERO,disabled);
        for (Object worker:List.of(cp,pan)) {
            boolean checkPoint=worker instanceof CpFailoverJobExecutor;
            for (String fieldName:checkPoint?List.of("DOWN","UP"):List.of("SUSPEND","FUNCTIONAL")) {
                var field=worker.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                String command=(String)field.get(null);
                for (var method:worker.getClass().getDeclaredMethods()) {
                    if (!List.of("gate",checkPoint?"command":"call").contains(method.getName())) continue;
                    method.setAccessible(true);
                    Object[] args=method.getParameterCount()==1?new Object[]{command}:new Object[]{null,command};
                    var error=assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> method.invoke(worker,args));
                    assertEquals(FailoverMutationSwitch.DISABLED,error.getCause().getMessage());
                }
            }
        }
    }

    @Test void noDefaultExecutorCanReturnSyntheticSuccess() {
        assertFalse(FailoverMutationSwitch.fromValue(null).enabled());
        for (FailoverDeviceExecutor executor:List.of(new CheckPointClusterXLExecutor(),new PaloAltoHaExecutor())) {
            for (var action:FailoverActionKind.values()) {
                var result=executor.executeAction("synthetic-member",action);
                assertFalse(result.successful());
                assertEquals(FailoverCommandResult.DeliveryCertainty.DEFINITELY_NOT_SUBMITTED,result.certainty());
            }
            var observation=executor.observePostcondition("synthetic-unit","synthetic-member-a","synthetic-member-b");
            assertFalse(observation.memberAObservation().observationSuccessful());
            assertFalse(observation.memberBObservation().observationSuccessful());
        }
    }
}
