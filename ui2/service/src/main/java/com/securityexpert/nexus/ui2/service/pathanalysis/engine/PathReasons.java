package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

/**
 * Fixed vocabulary of the path engine (F§4.7: a superset of the A2 hop reasons, which are carried verbatim). Codes
 * only: no value here carries an address, a name or a rule identity.
 */
public final class PathReasons {
    private PathReasons() {
    }

    // ---- verdicts ------------------------------------------------------------------------------------------------
    public static final String ALLOWED = "ALLOWED";
    public static final String DENIED = "DENIED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String PARTIAL = "PARTIAL";
    public static final String ALLOWED_TO_EDGE = "ALLOWED_TO_EDGE";
    /** A hop downstream of an UNKNOWN hop: its own evaluation holds only if the upstream unknown resolves favourably. */
    public static final String CONDITIONAL = "CONDITIONAL";

    // ---- branch terminations -------------------------------------------------------------------------------------
    public static final String DELIVERED = "DELIVERED";
    public static final String EXITS_KNOWN_TOPOLOGY = "EXITS_KNOWN_TOPOLOGY";
    public static final String BLOCKED = "BLOCKED";
    public static final String NO_ROUTE = "NO_ROUTE";
    public static final String ROUTING_LOOP = "ROUTING_LOOP";
    public static final String HOP_LIMIT = "HOP_LIMIT";
    public static final String ANALYSIS_BUDGET = "ANALYSIS_BUDGET";
    public static final String NEXT_HOP_AMBIGUOUS = "NEXT_HOP_AMBIGUOUS";
    public static final String NEXT_HOP_UNREACHABLE = "NEXT_HOP_UNREACHABLE";
    public static final String NEXT_HOP_KIND_UNKNOWN = "NEXT_HOP_KIND_UNKNOWN";
    public static final String NEXT_VR_UNRESOLVED = "NEXT_VR_UNRESOLVED";
    public static final String CONNECTED_SEGMENT_UNKNOWN = "CONNECTED_SEGMENT_UNKNOWN";
    public static final String ENGINE_DATA_ERROR = "ENGINE_DATA_ERROR";

    // ---- overall / branch reasons --------------------------------------------------------------------------------
    public static final String ALL_HOPS_ALLOWED = "ALL_HOPS_ALLOWED";
    public static final String DENIED_AT_HOP = "DENIED_AT_HOP";
    /** A hop denies, but an earlier hop is UNKNOWN: DENIED is only claimed when every hop before the block is decided. */
    public static final String DENIED_AFTER_UNKNOWN_HOP = "DENIED_AFTER_UNKNOWN_HOP";
    public static final String UNKNOWN_HOP = "UNKNOWN_HOP";
    public static final String MIXED_BRANCH_VERDICTS = "MIXED_BRANCH_VERDICTS";
    public static final String ECMP_TRUNCATED = "ECMP_TRUNCATED";
    public static final String ASYMMETRIC_RETURN = "ASYMMETRIC_RETURN";
    public static final String AMBIGUOUS_INGRESS = "AMBIGUOUS_INGRESS";
    public static final String INGRESS_NOT_FOUND = "INGRESS_NOT_FOUND";
    public static final String INGRESS_HINT_NOT_FOUND = "INGRESS_HINT_NOT_FOUND";
    public static final String INGRESS_INTERFACE_NOT_FOUND = "INGRESS_INTERFACE_NOT_FOUND";
    /**
     * A branch ended {@code NO_ROUTE} on a unit whose route table holds no route at all in that context (nothing was
     * collected or attributed there): a collection or attribution gap, not a proven absence of a route. Still UNKNOWN.
     */
    public static final String ROUTE_TABLE_EMPTY = "ROUTE_TABLE_EMPTY";

    // ---- hop reasons the engine adds (A2 reasons are carried as they are) ----------------------------------------
    public static final String POLICY_BINDING_UNPROVEN = "POLICY_BINDING_UNPROVEN";
    /** More than one stored policy names this unit's device and policy context as a target: none is provably its own. */
    public static final String POLICY_BINDING_AMBIGUOUS = "POLICY_BINDING_AMBIGUOUS";
    /**
     * The unit's topology context has no proven policy context: a Check Point VSID is not the virtual-system reference a
     * policy target carries (AGENTS.md identity law: no invented equivalence), so the hop is UNKNOWN.
     */
    public static final String POLICY_CONTEXT_UNPROVEN = "POLICY_CONTEXT_UNPROVEN";
    public static final String POLICY_SOURCE_FAILED = "POLICY_SOURCE_FAILED";
    public static final String POLICY_EVALUATION_FAILED = "POLICY_EVALUATION_FAILED";
    public static final String STALE_OR_OUT_OF_SYNC_POLICY = "STALE_OR_OUT_OF_SYNC_POLICY";
    public static final String UNSUPPORTED_NODE_VENDOR = "UNSUPPORTED_NODE_VENDOR";
    public static final String ACTIVE_MEMBER_UNPROVEN = "ACTIVE_MEMBER_UNPROVEN";
    public static final String RELATIONSHIP_INCONSISTENT = "RELATIONSHIP_INCONSISTENT";
    /** The route chosen by longest prefix has {@code is_active} NULL: whether it forwards is UNKNOWN. */
    public static final String ROUTE_ACTIVE_UNKNOWN = "ROUTE_ACTIVE_UNKNOWN";
    /** Several equally specific active routes that are neither all ECMP nor separated by a known metric. */
    public static final String ROUTE_SELECTION_AMBIGUOUS = "ROUTE_SELECTION_AMBIGUOUS";
    /** A route row whose IPv4 destination cannot be parsed: it might be more specific than the one chosen. */
    public static final String ROUTE_TABLE_UNPARSEABLE = "ROUTE_TABLE_UNPARSEABLE";
    /** Check Point: routing before or after destination NAT is not collected (§5) and the two lookups differ. */
    public static final String CP_ROUTE_AFTER_NAT_UNKNOWN = "CP_ROUTE_AFTER_NAT_UNKNOWN";
    /** The forwarding route is a discard (blackhole) route: the packet is dropped by routing. */
    public static final String ROUTE_DISCARD = "ROUTE_DISCARD";
    /** NAT was UNKNOWN: the packet is traced on untranslated, which the taint already marks as unproven. */
    public static final String NAT_UNKNOWN_TUPLE_UNCHANGED = "NAT_UNKNOWN_TUPLE_UNCHANGED";

    // ---- caveats (a verdict carrying one is never plain) ---------------------------------------------------------
    public static final String CLUSTER_UNCORROBORATED = "CLUSTER_UNCORROBORATED";
    public static final String CONFIG_DERIVED_INTERFACES = "CONFIG_DERIVED_INTERFACES";
    public static final String MANAGEMENT_DERIVED_PREFIX = "MANAGEMENT_DERIVED_PREFIX";
    public static final String PBR_PRESENT = "PBR_PRESENT";
    /** The return walk ended before reaching the source side without showing asymmetry: symmetry is unproven. */
    public static final String RETURN_PATH_UNPROVEN = "RETURN_PATH_UNPROVEN";
    /**
     * The source segment's prefix is held by several unjoined segments (duplicate private prefix): every candidate
     * first hop was traced and all of them gave the same decided verdict, so the verdict does not depend on which one
     * the source sits behind. The ingress itself stays {@code AMBIGUOUS_INGRESS}.
     */
    public static final String INGRESS_CANDIDATES_AGREE = "INGRESS_CANDIDATES_AGREE";
    /**
     * The query source is an address an enrolled unit holds itself: traffic the unit originates is not transit traffic,
     * and the rules that govern it (implied rules, management-plane handling) are not modelled. The result traces a
     * packet entering on that segment.
     */
    public static final String SOURCE_IS_UNIT_ADDRESS = "SOURCE_IS_UNIT_ADDRESS";
    /** The query destination is an address an enrolled unit holds itself (to-the-box traffic, not modelled as such). */
    public static final String DESTINATION_IS_UNIT_ADDRESS = "DESTINATION_IS_UNIT_ADDRESS";

    // ---- notes / warnings ----------------------------------------------------------------------------------------
    public static final String HA_ROUTE_DRIFT = "HA_ROUTE_DRIFT";
    public static final String OUT_OF_STATE_DROP_LIKELY = "OUT_OF_STATE_DROP_LIKELY";
    public static final String INGRESS_INFERRED = "INGRESS_INFERRED";
    public static final String RETURN_NOT_COMPUTED_AT_EDGE = "RETURN_NOT_COMPUTED_AT_EDGE";
    /** Hop note: the route that left the known topology is a default route ({@code 0.0.0.0/0}). */
    public static final String EXIT_VIA_DEFAULT_ROUTE = "EXIT_VIA_DEFAULT_ROUTE";
    /** Hop note: the route left through a VSX warp link, kept as a cloud segment until a measured fixture proves it. */
    public static final String EXIT_VIA_VSX_WARP = "EXIT_VIA_VSX_WARP";
    /**
     * Branch warning: the branch left the known topology although the destination lies on a segment an enrolled unit
     * is attached to. The unit in between is not enrolled (or not attached); the path beyond the exit is not evaluated.
     */
    public static final String DESTINATION_IN_KNOWN_TOPOLOGY = "DESTINATION_IN_KNOWN_TOPOLOGY";

    // ---- ingress modes and return statuses -----------------------------------------------------------------------
    public static final String GIVEN = "GIVEN";
    public static final String INFERRED = "INFERRED";
    public static final String SYMMETRIC = "SYMMETRIC";
    public static final String ASYMMETRIC = "ASYMMETRIC";
    public static final String INCOMPLETE = "INCOMPLETE";
    public static final String NOT_COMPUTED = "NOT_COMPUTED";
}
