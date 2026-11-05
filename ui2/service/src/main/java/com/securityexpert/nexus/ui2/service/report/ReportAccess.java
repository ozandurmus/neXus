package com.securityexpert.nexus.ui2.service.report;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Who may get what (Reports R1 contract "Masking and RBAC"). The route map already gated {@code report_read} or
 * {@code report_generate}; this decides the rest:
 *
 * <ul>
 *   <li>the read action of every underlying domain a template uses (a missing one refuses the report);</li>
 *   <li>RAW: only a session holding {@code report_raw_view}, and never a replay viewer (aiview), even one also bound
 *       to an admin role.</li>
 * </ul>
 */
public final class ReportAccess {

    private final RbacEvaluator rbac;
    private final ActionRegistry registry;
    private final Clock clock;

    public ReportAccess(RbacEvaluator rbac, ActionRegistry registry) {
        this(rbac, registry, Clock.systemUTC());
    }

    ReportAccess(RbacEvaluator rbac, ActionRegistry registry, Clock clock) {
        this.rbac = Objects.requireNonNull(rbac, "rbac");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** True when the actor holds the action: open actions need a signed-in actor, the rest one of their roles. */
    public boolean holds(String actor, String actionId) {
        if (actor == null || actor.isBlank()) return false;
        var descriptor = registry.find(actionId);
        if (descriptor.isEmpty()) return false; // fail closed on an unknown action
        Set<String> roles = descriptor.get().requiredRoleTokens();
        if (roles.isEmpty()) return true;
        return rbac.evaluateAny(actor, roles, clock.instant()).outcome() == AuthzOutcome.PERMITTED;
    }

    /** The template's underlying domain reads the actor lacks; empty means the report may be built. */
    public List<String> missingDomainReads(String actor, ReportTemplate template) {
        List<String> missing = new ArrayList<>();
        for (String action : template.domainReadActions()) {
            if (!holds(actor, action)) missing.add(action);
        }
        return missing;
    }

    /** RAW authority: never a replay viewer. */
    public boolean mayRaw(String actor, boolean replayViewer) {
        return !replayViewer && holds(actor, ActionRegistry.REPORT_RAW_VIEW);
    }
}
