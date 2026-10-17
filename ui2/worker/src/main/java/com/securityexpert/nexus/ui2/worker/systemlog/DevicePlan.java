package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.List;

/**
 * The plans of every incremental source of one device for one run, in the fixed run order of contract section 3.2,
 * planned against one shared per-device budget.
 */
public record DevicePlan(List<SourcePlan> sources, long plannedBytes) {

    public DevicePlan {
        sources = List.copyOf(sources);
    }

    public SourcePlan of(SourceFamily family) {
        for (SourcePlan plan : sources) {
            if (plan.family() == family) {
                return plan;
            }
        }
        throw new IllegalArgumentException("no plan for " + family);
    }
}
