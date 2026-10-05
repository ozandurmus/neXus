package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class PolicyCollectionTraceTest {
    @Test void aggregatesPackagesAcrossDomainsAndWorkerTracesWithoutResettingRulesOrActivity() throws Exception {
        var updates = new ArrayList<PolicyCollectionTrace.LayerProgress>();
        try (var trace = new PolicyCollectionTrace("run-1", (step, total) -> {}, measurement -> {}, updates::add)) {
            PolicyCollectionTrace.timeout(Duration.ofSeconds(60));
            PolicyCollectionTrace.domains(2);
            PolicyCollectionTrace.packages(2);
            PolicyCollectionTrace.layer("package-1", 2, 2, 2701);
            PolicyCollectionTrace.done("package-1");
            var worker = PolicyCollectionTrace.worker(() -> {
                PolicyCollectionTrace.layer("package-2", 1, 3, 40);
                PolicyCollectionTrace.result(System.nanoTime(), 100, "Completed");
                return null;
            });
            var thread = new Thread(() -> { try { worker.call(); } catch (Exception failure) { throw new AssertionError(failure); } });
            thread.start(); thread.join();
            PolicyCollectionTrace.packages(1);
            PolicyCollectionTrace.layer("package-2", 2, 3, 80);
            PolicyCollectionTrace.layer("package-2", 2, 3, 0); // Discarding failed pages must not erase fetched rules.
            PolicyCollectionTrace.done("package-2");
            PolicyCollectionTrace.done("package-2");
            long activity = updates.get(updates.size() - 1).lastActivity();
            PolicyCollectionTrace.result(System.nanoTime(), -1, "TimedOut");
            var last = updates.get(updates.size() - 1);
            assertEquals(2, last.packagesDone());
            assertEquals(3, last.packagesTotal());
            assertEquals(2, last.domainsDone());
            assertEquals(2, last.domainsTotal());
            assertEquals(2781, last.rules());
            assertEquals(2, last.layer());
            assertEquals(3, last.layers());
            assertEquals(60, last.readTimeoutSeconds());
            assertTrue(activity > 0);
            assertEquals(activity, last.lastActivity());
            for (int i = 1; i < updates.size(); i++) assertTrue(updates.get(i).step() > updates.get(i - 1).step());
        }
    }
}
