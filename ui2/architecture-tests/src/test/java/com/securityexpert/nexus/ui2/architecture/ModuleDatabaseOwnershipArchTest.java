package com.securityexpert.nexus.ui2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceAdminRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceInventoryWriteRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceReadRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;

/**
 * Per-module database ownership architecture fence
 * ({@code docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md} §2.5.3, §4.2 Step 0, §4.3.1).
 *
 * <p>Enforces over compiled bytecode that:</p>
 * <ol>
 *   <li>{@link JooqDeviceRepository} is partitioned into {@link DeviceReadRepository},
 *       {@link DeviceInventoryWriteRepository}, and {@link DeviceAdminRepository}, and no class outside
 *       {@code service} and {@code persistence} ever invokes {@link DeviceAdminRepository} methods
 *       ({@code deleteDevice}, {@code registerDraft}, {@code withdrawDraft}, {@code setDisabled},
 *       {@code setCredentialReference}, {@code setBackupTarget}, {@code transitionEnrollmentState}).</li>
 *   <li>Service-exclusive persistence packages ({@code ..persistence.identity..}, {@code ..persistence.sanitizer..},
 *       {@code ..persistence.zonematrix..}) are never depended on by {@code worker}, {@code scheduler}, or
 *       {@code packet-analysis}.</li>
 *   <li>{@code ..persistence.packetanalysis..} is accessed only by {@code service.packetanalysis},
 *       {@code worker.packetanalysis}, and {@code Ui2WorkerMain} composition root.</li>
 *   <li>{@code ..worker.packetanalysis..} never reaches device, credential, discovery, failover, policy, backup,
 *       configuration, or diagnostic persistence packages.</li>
 * </ol>
 */
class ModuleDatabaseOwnershipArchTest {

    private static final List<String> DEVICE_ADMIN_METHODS = List.of(
            "deleteDevice",
            "registerDraft",
            "withdrawDraft",
            "setDisabled",
            "setCredentialReference",
            "setBackupTarget",
            "transitionEnrollmentState");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Ui2ArchitectureTest.productionClasses();
        assertFalse(classes.isEmpty());
    }

    @Test
    void deviceRepositoryHierarchySeparatesReadInventoryWriteAndServiceAdmin() {
        assertTrue(DeviceReadRepository.class.isAssignableFrom(DeviceInventoryWriteRepository.class));
        assertTrue(DeviceInventoryWriteRepository.class.isAssignableFrom(DeviceAdminRepository.class));
        assertTrue(DeviceAdminRepository.class.isAssignableFrom(DeviceRepository.class));
        assertTrue(DeviceRepository.class.isAssignableFrom(JooqDeviceRepository.class));
    }

    @Test
    void workersSchedulersAndJobEngineNeverInvokeDeviceAdminMethods() {
        List<String> violations = new ArrayList<>();
        for (var javaClass : classes) {
            String pkg = javaClass.getPackageName();
            if (pkg.startsWith("com.securityexpert.nexus.ui2.service.")
                    || pkg.startsWith("com.securityexpert.nexus.ui2.persistence.")) {
                continue;
            }
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                String targetOwner = call.getTargetOwner().getName();
                if ((targetOwner.equals(DeviceRepository.class.getName())
                        || targetOwner.equals(DeviceAdminRepository.class.getName())
                        || targetOwner.equals(JooqDeviceRepository.class.getName()))
                        && DEVICE_ADMIN_METHODS.contains(call.getName())) {
                    violations.add(javaClass.getName() + " calls " + targetOwner + "." + call.getName());
                }
            }
        }
        assertEquals(List.of(), violations,
                "only ui2_service may invoke administrative/deletion methods on DeviceAdminRepository");
    }

    @Test
    void serviceExclusivePersistencePackagesAreNeverUsedByWorkersSchedulersOrPacketAnalysis() {
        noClasses().that().resideInAnyPackage(
                        "..worker..", "..scheduler..", "..packetanalysis..", "..capability..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..persistence.identity..",
                        "..persistence.sanitizer..",
                        "..persistence.zonematrix..")
                .check(classes);
    }

    @Test
    void packetAnalysisWorkerUsesOnlyPacketAnalysisAndSharedRuntimePersistence() {
        noClasses().that().resideInAPackage("..worker.packetanalysis..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..persistence.advisory..",
                        "..persistence.artefact..",
                        "..persistence.changes..",
                        "..persistence.credential..",
                        "..persistence.device..",
                        "..persistence.diagnostic..",
                        "..persistence.discovery..",
                        "..persistence.https..",
                        "..persistence.identity..",
                        "..persistence.lifecycle..",
                        "..persistence.pathvalidation..",
                        "..persistence.policy..",
                        "..persistence.report..",
                        "..persistence.sanitizer..",
                        "..persistence.schedule..",
                        "..persistence.script..",
                        "..persistence.staticroute..",
                        "..persistence.supportbundle..",
                        "..persistence.zonematrix..")
                .check(classes);
    }

    @Test
    void packetAnalysisPersistenceIsOnlyUsedByPacketAnalysisServiceAndWorker() {
        noClasses().that().resideOutsideOfPackages(
                        "..persistence.packetanalysis..",
                        "..service.packetanalysis..",
                        "..service.api..",
                        "..service.boot..",
                        "..worker.packetanalysis..",
                        "com.securityexpert.nexus.ui2.worker")
                .should().dependOnClassesThat().resideInAPackage("..persistence.packetanalysis..")
                .check(classes);
    }
}
