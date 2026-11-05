package com.securityexpert.nexus.ui2.service.report;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.securityexpert.nexus.ui2.service.privacy.ReportProjectionMasker;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** Cross-package test wiring: a real {@link ReportService} over the synthetic estate, a temp store and a given RBAC. */
public final class ReportTestSupport {

    private ReportTestSupport() {
    }

    public static ReportService service(RbacEvaluator rbac, Path root, TopologyNamePseudonymizer names,
            SubnetPreservingIpMasker addresses) {
        return new ReportService(new ReportFixtures.Data(), new InMemoryReportRunStore(), ReportServiceTest.store(root),
                new ReportDocumentMasker(new ReportProjectionMasker(names, addresses)),
                new ReportExecutor(Runnable::run, 2, Duration.ofSeconds(1)), new ReportAccess(rbac, new ActionRegistry()),
                Clock.systemUTC());
    }

    public static List<String> rawValues() {
        return ReportFixtures.RAW_VALUES;
    }
}
