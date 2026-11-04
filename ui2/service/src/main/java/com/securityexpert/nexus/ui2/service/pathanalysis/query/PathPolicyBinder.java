package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.HopPolicySource;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathReasons;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator;

/**
 * Stage A5a production binding of the A4 {@link HopPolicySource} port to stored policy (PATH_ANALYSIS_DESIGN.md §5,
 * FROZEN): per unit, the routing member's device and policy context are matched to the one stored snapshot whose targets
 * name exactly that device and context; the snapshot comes through the policy parse cache, the Check Point
 * {@code policy_target_binding} rows and the Palo Alto serial are added, and the staleness rule is applied.
 *
 * <p>Context mapping (topology {@code device_interface.context} vocabulary to {@code PolicySnapshot.Target.context}):
 * {@code physical} is the no-virtual-system context {@code ''} for both vendors (V185: {@code ''} = physical gateway);
 * a Palo Alto vsys id is the same vsys name the policy target carries (exact string equality, nothing else); a Check
 * Point VSID has no proven relation to the virtual-system reference a VS policy target carries, so such a unit is
 * {@code POLICY_CONTEXT_UNPROVEN} (AGENTS.md identity law: no invented equivalence). A Panorama target listed without
 * a vsys ({@code ''}) is not equated with {@code vsys1} either: that relation is unmeasured.</p>
 *
 * <p>Staleness (§5): a snapshot collected more than {@link #MAX_POLICY_AGE} before the query, with an unreadable
 * collection time, or whose matching target is {@code OUT_OF_SYNC}, is bound {@code stale}: the hop is
 * {@code UNKNOWN (STALE_OR_OUT_OF_SYNC_POLICY)} and the last-known evaluation is context only.</p>
 *
 * <p>Prepared per-device rule views are kept in a small bounded cache keyed by policy, device, context, serial and
 * binding rows, valid only while the policy parse cache hands out the same snapshot instance.</p>
 */
public final class PathPolicyBinder {

    public static final Duration MAX_POLICY_AGE = Duration.ofHours(24);
    static final String CONTEXT_PHYSICAL = "physical";
    private static final int PREPARED_KEPT = 16;

    private final PathPolicyCatalog catalog;

    private record PreparedKey(String policyId, String deviceId, String context, String serial,
            HopPolicyEvaluator.Binding binding) {
    }

    private record PreparedEntry(WeakReference<PolicySnapshot> snapshot, HopPolicyEvaluator.Prepared prepared) {
    }

    private final Map<PreparedKey, PreparedEntry> prepared = new LinkedHashMap<>(16, 0.75f, true);

    public PathPolicyBinder(PathPolicyCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** A source for one query at {@code now}: the catalog is read at most once, each unit is bound once by the engine. */
    public HopPolicySource forQuery(Instant now) {
        Objects.requireNonNull(now, "now");
        return new HopPolicySource() {
            private List<PolicySnapshot.Metadata> metadata;

            @Override
            public HopPolicy policyFor(TopologyGraph.Node node) {
                if (metadata == null) {
                    metadata = List.copyOf(catalog.catalog());
                }
                return bind(node, metadata, now);
            }
        };
    }

    /** The policy context a topology context maps to, or empty when the relation is not proven. */
    static Optional<String> policyContext(String vendor, String topologyContext) {
        if (topologyContext == null || topologyContext.isBlank()) {
            return Optional.empty();
        }
        if (CONTEXT_PHYSICAL.equals(topologyContext)) {
            return Optional.of("");
        }
        return TopologyInput.VENDOR_PALO_ALTO.equals(vendor) ? Optional.of(topologyContext) : Optional.empty();
    }

    private HopPolicySource.HopPolicy bind(TopologyGraph.Node node, List<PolicySnapshot.Metadata> metadata, Instant now) {
        if (node.kind() == TopologyGraph.NodeKind.CLOUD || node.activeMemberDeviceId().isEmpty()) {
            return new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_UNPROVEN);
        }
        String policyVendor = TopologyInput.VENDOR_CHECK_POINT.equals(node.vendor()) ? "CP"
                : TopologyInput.VENDOR_PALO_ALTO.equals(node.vendor()) ? "PAN" : null;
        if (policyVendor == null) {
            return new HopPolicySource.Unbound(PathReasons.UNSUPPORTED_NODE_VENDOR);
        }
        Optional<String> context = policyContext(node.vendor(), node.context());
        if (context.isEmpty()) {
            return new HopPolicySource.Unbound(PathReasons.POLICY_CONTEXT_UNPROVEN);
        }
        String deviceId = node.activeMemberDeviceId().get();
        List<PolicySnapshot.Metadata> candidates = new ArrayList<>();
        for (PolicySnapshot.Metadata m : metadata) {
            if (policyVendor.equals(m.vendor()) && targets(m, deviceId, context.get())) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            return new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_UNPROVEN);
        }
        if (candidates.size() > 1) {
            return new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_AMBIGUOUS);
        }
        String policyId = candidates.get(0).id();
        Optional<PolicySnapshot> found = catalog.find(policyId);
        if (found.isEmpty() || !policyVendor.equals(found.get().metadata().vendor())
                || !targets(found.get().metadata(), deviceId, context.get())) {
            return new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_UNPROVEN);
        }
        PolicySnapshot snapshot = found.get();
        String serial = "PAN".equals(policyVendor) ? catalog.panSerial(deviceId).orElse(null) : null;
        var device = new HopPolicyEvaluator.Device(deviceId, context.get(), serial);
        var binding = "CP".equals(policyVendor)
                ? HopPolicyEvaluator.Binding.of(policyId, catalog.bindings(policyId), device)
                : HopPolicyEvaluator.Binding.NONE;
        Optional<Instant> collectedAt = instant(snapshot.metadata().collectedAt());
        boolean outOfSync = snapshot.metadata().targets().stream()
                .anyMatch(t -> t.deviceId().equals(deviceId) && t.context().equals(context.get())
                        && "OUT_OF_SYNC".equals(t.syncStatus()));
        boolean stale = outOfSync || collectedAt.isEmpty() || collectedAt.get().plus(MAX_POLICY_AGE).isBefore(now);
        return new HopPolicySource.Bound(prepare(policyId, snapshot, device, binding), collectedAt, stale);
    }

    private static boolean targets(PolicySnapshot.Metadata m, String deviceId, String context) {
        return m.targets().stream().anyMatch(t -> deviceId.equals(t.deviceId()) && context.equals(t.context()));
    }

    private static Optional<Instant> instant(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException unreadable) {
            return Optional.empty();
        }
    }

    private HopPolicyEvaluator.Prepared prepare(String policyId, PolicySnapshot snapshot, HopPolicyEvaluator.Device device,
            HopPolicyEvaluator.Binding binding) {
        var key = new PreparedKey(policyId, device.deviceId(), device.context(), device.panSerial(), binding);
        synchronized (prepared) {
            PreparedEntry entry = prepared.get(key);
            if (entry != null && entry.snapshot().get() == snapshot) {
                return entry.prepared();
            }
        }
        // Prepared outside the lock: a large policy must not hold every other query.
        HopPolicyEvaluator.Prepared made = HopPolicyEvaluator.prepare(snapshot, device, binding);
        synchronized (prepared) {
            prepared.put(key, new PreparedEntry(new WeakReference<>(snapshot), made));
            while (prepared.size() > PREPARED_KEPT) {
                prepared.remove(prepared.keySet().iterator().next());
            }
        }
        return made;
    }

    /** Diagnostics and tests: how many prepared device views are held. */
    int preparedHeld() {
        synchronized (prepared) {
            return prepared.size();
        }
    }
}
