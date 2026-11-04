package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator;

/**
 * Binds a topology unit to the stage A2 rule engine prepared for its routing member (F§4.4): the stored policy
 * snapshot proven to be that device's (and virtual system's) policy, with its {@code policy_target_binding} rows and,
 * for Palo Alto, the firewall serial. The engine asks once per unit per query and never reads storage itself; the
 * production binding over stored snapshots is wired by the API stage (A5a).
 *
 * <p>An implementation must not throw for missing or inconsistent data -- it answers {@link Unbound} with a reason --
 * but the engine treats a throw as {@link PathReasons#POLICY_SOURCE_FAILED} anyway.</p>
 */
@FunctionalInterface
public interface HopPolicySource {

    HopPolicy policyFor(TopologyGraph.Node node);

    sealed interface HopPolicy permits Bound, Unbound {
    }

    /**
     * @param stale the snapshot is older than the freshness rule allows, or the manager reports it out of sync: the
     *              hop is {@code UNKNOWN (STALE_OR_OUT_OF_SYNC_POLICY)} and the last-known evaluation is context only
     */
    record Bound(HopPolicyEvaluator.Prepared prepared, Optional<Instant> collectedAt, boolean stale) implements HopPolicy {
        public Bound {
            Objects.requireNonNull(prepared, "prepared");
            collectedAt = collectedAt == null ? Optional.empty() : collectedAt;
        }
    }

    /** No policy is provably this unit's; {@code reason} is a fixed-vocabulary code. */
    record Unbound(String reason) implements HopPolicy {
        public Unbound {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Binds nothing: every hop is {@code UNKNOWN (POLICY_BINDING_UNPROVEN)}. */
    HopPolicySource NONE = node -> new Unbound(PathReasons.POLICY_BINDING_UNPROVEN);
}
