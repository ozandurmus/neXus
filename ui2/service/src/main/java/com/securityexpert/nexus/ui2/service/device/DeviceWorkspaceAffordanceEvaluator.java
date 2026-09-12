package com.securityexpert.nexus.ui2.service.device;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.service.security.ActionDescriptor;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Contract §6.1's {@code action_affordance} evaluation, as a read-model
 * step reusable without a controller: for a fixed, caller-supplied list of
 * {@code action_id}s, runs {@code E4} for the current actor against each
 * one and records every evaluation in {@code authz_decisions} (§6.3 --
 * "Every affordance evaluation... writes an {@code authz_decisions} row",
 * not only a refusal).
 *
 * <h2>What this class is not</h2>
 *
 * <p>It does not decide <em>which</em> action ids belong on the device
 * workspace. §6.4 fixes that the refused affordances refer to actions
 * "owned and served elsewhere" (the device-model movement's confirm/
 * disable/enable actions, none of which {@link ActionRegistry} currently
 * seeds) -- inventing a device-scoped action id here would be a new
 * action-registry decision, out of this task's scope (the {@code security}
 * package) and out of this contract's own scope (§1.1: no submission path
 * is added). The only action {@link ActionRegistry} currently seeds that is
 * even device-adjacent is {@link ActionRegistry#DEVICE_REGISTER} (device
 * creation, not a per-device action); callers exercising this class today
 * necessarily use it as a mechanism proof, not as the shipped screen's
 * action set. See the task report for this gap, carried forward rather
 * than resolved here.</p>
 *
 * <p>It also proves nothing about the HTTP layer: contract §6.1's promise
 * that the {@code action_affordance} key set is identical across a GET's
 * live evaluation and a subsequent activation's {@code 403} body (test 5,
 * {@code GetAffordanceAndPostRefusalAgree}) requires two routes to compare,
 * which this class -- and this task -- does not add.</p>
 */
public final class DeviceWorkspaceAffordanceEvaluator {

    private final ActionRegistry actionRegistry;
    private final RbacEvaluator rbacEvaluator;
    private final AuthzDecisionRepository authzDecisionRepository;

    public DeviceWorkspaceAffordanceEvaluator(ActionRegistry actionRegistry, RbacEvaluator rbacEvaluator,
            AuthzDecisionRepository authzDecisionRepository) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.rbacEvaluator = Objects.requireNonNull(rbacEvaluator, "rbacEvaluator");
        this.authzDecisionRepository = Objects.requireNonNull(authzDecisionRepository, "authzDecisionRepository");
    }

    /**
     * @param actionIds        the declared action ids for this screen (§6.1: "The map's key set does not vary
     *                          by actor" -- callers must pass the same list for every actor over the same device)
     * @param sessionId         the acting session's id, recorded on every {@code authz_decisions} row
     * @param actorFingerprint  the acting session's opaque actor fingerprint
     * @param targetRef         the workspace's {@code device_id}, recorded as {@code authz_decisions.target_ref}
     * @return an insertion-ordered map with exactly {@code actionIds}' keys, one entry per action, in order
     */
    public Map<String, ActionAffordanceView> evaluate(List<String> actionIds, String sessionId,
            String actorFingerprint, String targetRef, Instant now) {
        Objects.requireNonNull(actionIds, "actionIds");
        Map<String, ActionAffordanceView> result = new LinkedHashMap<>();
        for (String actionId : actionIds) {
            ActionDescriptor descriptor = actionRegistry.find(actionId).orElseThrow(() -> new IllegalStateException(
                    "declared device workspace action_id not present in ActionRegistry: " + actionId));
            RbacEvaluator.Decision decision = rbacEvaluator.evaluate(actorFingerprint, descriptor.requiredRoleToken(),
                    now);
            authzDecisionRepository.insert(sessionId, actorFingerprint, actionId, Optional.of(targetRef),
                    decision.outcome(), decision.authority(), decision.reasonCode(), decision.bindingId());
            result.put(actionId, new ActionAffordanceView(decision.outcome(), decision.authority(),
                    decision.reasonCode()));
        }
        return result;
    }
}
