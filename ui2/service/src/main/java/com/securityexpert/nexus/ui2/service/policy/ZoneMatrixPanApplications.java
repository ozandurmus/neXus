package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.util.*;

/**
 * The Palo Alto application names an {@code application-default} rule is restricted to, for zone matrix delivery step 7
 * ({@code docs/design/ZONE_MATRIX_CONTRACT.md}, FROZEN rev 2, sections 4.2 and 4.3): a cell entry
 * {@code app:pan:<application-name>} is compared with these names by exact string equality, never folded or trimmed.
 *
 * <p>The application cell is resolved by the shadowing resolver's {@link Resolver#applications} (same object model,
 * cycle/depth limit and budget as hygiene): application any is any; an application group is the union of its members;
 * a stored custom application and a content-provided (predefined App-ID) application are each one opaque reference.
 * Each reference is then turned into its name only when the reference is exactly the one the mapper minted for that
 * name ({@code ref(snapshot, "application", name)}); otherwise the set is unknown.</p>
 *
 * <p>Fail closed: an application filter, a missing or unresolved object, an object of another shape, an empty group,
 * a group cycle or depth limit gives no name set and a reason from the fixed section 5.4 vocabulary. Application
 * {@code any} with {@code application-default} is not decided by section 4.3 ("a rule with application any is a port
 * rule", but application-default carries no port list): no name set and no reason, so the evaluator keeps
 * "application ports not collected".</p>
 */
final class ZoneMatrixPanApplications {
    private ZoneMatrixPanApplications() {}

    /** {@code known} true: {@code names} is the complete, non-empty set; else {@code problem} names why (null for any). */
    record Names(Set<String> names, boolean known, String problem) {
        static final Names ANY = new Names(Set.of(), false, null);
        static Names unknown(String problem) { return new Names(Set.of(), false, problem); }
    }

    static Names resolve(PolicySnapshot snapshot, Cell appCell, Budget budget) {
        String metaId = snapshot.metadata() == null ? null : snapshot.metadata().id();
        if (metaId == null || metaId.isBlank()) return Names.unknown("unresolved object");
        Selector selector;
        try {
            selector = Resolver.shadowing(snapshot, budget).applications(appCell);
        } catch (Unknown u) {
            if ("analysis budget".equals(u.getMessage())) throw u;
            return Names.unknown(reason(u.getMessage()));
        }
        if (selector.any()) return Names.ANY;
        if (selector.tokens().isEmpty() || !selector.ranges().isEmpty()) return Names.unknown("unresolved object");
        Set<String> names = new TreeSet<>();
        for (String reference : new TreeSet<>(selector.tokens())) {
            budget.spend();
            PolicyObject o = snapshot.objects().get(reference);
            if (o == null || o.name() == null || o.name().isEmpty()
                    || !reference.equals(ref(metaId, "application", o.name()))) {
                return Names.unknown("unresolved object");
            }
            names.add(o.name());
        }
        return new Names(Collections.unmodifiableSet(names), true, null);
    }

    private static String reason(String raw) {
        if (raw == null) return "dynamic or unsupported object type";
        return switch (raw) {
            case "missing selector", "unresolved object" -> "unresolved object";
            case "group cycle or depth limit" -> "group cycle or depth limit";
            case "empty or dynamic group" -> "empty or dynamic group";
            case "negated selector" -> "negated selector";
            default -> "dynamic or unsupported object type";
        };
    }
}
