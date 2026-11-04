package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;
import java.util.function.Function;

/**
 * Path analysis stage A2 (docs/design/PATH_ANALYSIS_DESIGN.md §5, §7; PATH_ANALYSIS_VENDOR_SEMANTICS.md, both FROZEN): the
 * rule engine one hop of a path uses. Given one enrolled device (and virtual-system context), a 5-tuple without source
 * port, the zones (Palo Alto) and that device's stored policy snapshot, it answers which rule decides and with what
 * verdict -- {@code ALLOWED}, {@code DENIED} or {@code UNKNOWN} with a reason code -- and which NAT rule translates the
 * tuple. Management intent only: never observed reachability, never a device read.
 *
 * <p>Security evaluation is {@link PolicyLookup} (budgeted three-valued first match, CP ordered and inline layers, PAN
 * pre/local/post phases) over a per-device view of the snapshot; the resolver is not duplicated. The view applies what
 * only a known device can decide:
 * <ul>
 * <li>Check Point install-on (gap G7): a rule whose install-on does not include the device is skipped (disabled in the
 * view) only when {@code policy_target_binding} proves it -- every install-on reference is a gateway object bound to other
 * devices and to none of this device's contexts. A rule installed on the device, on "any" or on "Policy Targets" (the
 * package installation targets, A0 §3a/§3b; the device is a package target or the hop is UNKNOWN before any rule) applies.
 * Anything else stays a rule that may match: {@code INSTALL_ON_UNPROVEN}, the hop UNKNOWN if it precedes the match.</li>
 * <li>Palo Alto {@code target}/{@code negate} (gap G8, A0 §4d): no target or nothing selected without negate = every
 * firewall; listed devices = only those; negate = all except those; negate with nothing selected = no firewall; tags,
 * vsys restrictions, an unrecognized shape, an unknown negate value or an unknown device serial = {@code PAN_TARGET_UNPROVEN}.
 * Serials are compared by exact string equality only.</li>
 * <li>{@code application-default} (decision D-5): matched on the application's default ports where the stored application
 * object carries them; such a match is never plain ALLOWED (caveat {@code APP_ID_ASSUMED}). Without stored ports, or with
 * application any, the rule stays unresolvable ({@code APP_DEFAULT_PORTS_NOT_STORED}).</li>
 * <li>Palo Alto default rules (§5): when no rule matches, the collected {@code intrazone-default}/{@code interzone-default}
 * override of a local (unmanaged) firewall decides; without it, or for a Panorama snapshot (the firewall level, which
 * takes precedence, is not collected, A0 §4c), the hop is UNKNOWN. A vendor default is never assumed.</li>
 * </ul>
 * NAT evaluation is {@link HopNatEvaluator}; the per-hop order of NAT and access is {@link #checkPoint} and
 * {@link #paloAlto}.
 */
public final class HopPolicyEvaluator {
    private HopPolicyEvaluator() {}

    public static final int DEFAULT_WORK = 250_000;
    public static final String ALLOWED = "ALLOWED", DENIED = "DENIED", UNKNOWN = "UNKNOWN";

    // Hop and security reason codes (fixed vocabulary; detail carries the PolicyLookup reason where there is one).
    public static final String FIRST_MATCHING_RULE = "FIRST_MATCHING_RULE";
    public static final String ALL_ORDERED_LAYERS_ALLOWED = "ALL_ORDERED_LAYERS_ALLOWED";
    public static final String ORDERED_LAYER_UNKNOWN = "ORDERED_LAYER_UNKNOWN";
    public static final String EARLIER_RULE_UNRESOLVED = "EARLIER_RULE_UNRESOLVED";
    public static final String CP_CLEANUP_NOT_COLLECTED = "CP_CLEANUP_NOT_COLLECTED";
    public static final String PAN_DEFAULT_RULE_OVERRIDE = "PAN_DEFAULT_RULE_OVERRIDE";
    public static final String PAN_DEFAULT_RULE_NOT_COLLECTED = "PAN_DEFAULT_RULE_NOT_COLLECTED";
    public static final String PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED = "PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED";
    public static final String ZONE_REQUIRED = "ZONE_REQUIRED";
    public static final String TARGET_CONTEXT_UNPROVEN = "TARGET_CONTEXT_UNPROVEN";
    public static final String STALE_OR_OUT_OF_SYNC_POLICY = "STALE_OR_OUT_OF_SYNC_POLICY";
    public static final String SNAPSHOT_INCOMPLETE = "SNAPSHOT_INCOMPLETE";
    public static final String ANALYSIS_BUDGET = "ANALYSIS_BUDGET";
    public static final String UNSUPPORTED_PROTOCOL = "UNSUPPORTED_PROTOCOL";
    public static final String UNSUPPORTED_VENDOR = "UNSUPPORTED_VENDOR";
    public static final String LOOKUP_UNKNOWN = "LOOKUP_UNKNOWN";
    public static final String NAT_UNKNOWN = "NAT_UNKNOWN";
    public static final String CP_ACCESS_NAT_ORDER_UNKNOWN = "CP_ACCESS_NAT_ORDER_UNKNOWN";
    public static final String POST_NAT_EGRESS_ZONE_UNKNOWN = "POST_NAT_EGRESS_ZONE_UNKNOWN";
    // Per-rule reasons the device view adds to undecidable rules.
    public static final String INSTALL_ON_UNPROVEN = "INSTALL_ON_UNPROVEN";
    public static final String PAN_TARGET_UNPROVEN = "PAN_TARGET_UNPROVEN";
    public static final String APP_DEFAULT_PORTS_NOT_STORED = "APP_DEFAULT_PORTS_NOT_STORED";
    // Caveats: a verdict that carries one is never plain.
    public static final String APP_ID_ASSUMED = "APP_ID_ASSUMED";
    public static final String POLICY_SYNC_UNKNOWN = "POLICY_SYNC_UNKNOWN";
    public static final String PAN_LOCAL_RULES_NOT_COLLECTED = "PAN_LOCAL_RULES_NOT_COLLECTED";
    // Notes: context for the path engine, no verdict effect.
    public static final String CP_IMPLIED_RULES_NOT_MODELED = "CP_IMPLIED_RULES_NOT_MODELED";
    public static final String CP_ROUTE_AFTER_NAT_UNKNOWN = "CP_ROUTE_AFTER_NAT_UNKNOWN";
    public static final String CP_SOURCE_NAT_ACCESS_ON_PRE_NAT = "CP_SOURCE_NAT_ACCESS_ON_PRE_NAT";
    public static final String PAN_SECURITY_PRE_NAT_PORT = "PAN_SECURITY_PRE_NAT_PORT";

    /** An IPv4 5-tuple without source port (§5). {@code protocol} is lower case; the port is the destination port. */
    public record Tuple(String source, String destination, String protocol, int destinationPort) {
        public Tuple {
            PolicyRuleQuery.interval(requireAddress(source)); PolicyRuleQuery.interval(requireAddress(destination));
            if (protocol == null || !protocol.matches("[a-z][a-z0-9-]{0,15}")) throw new IllegalArgumentException("Invalid hop protocol");
            if (destinationPort < 0 || destinationPort > 65535) throw new IllegalArgumentException("Invalid hop port");
        }
        private static String requireAddress(String value) {
            if (value == null || !value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("Invalid hop address");
            return value;
        }
        /** The PolicyLookup service of this tuple, or {@code null} for a protocol other than TCP or UDP. */
        String service() { return Set.of("tcp", "udp").contains(protocol) ? protocol + "/" + destinationPort : null; }
        long sourceValue() { return PolicyRuleQuery.interval(source)[0]; }
        long destinationValue() { return PolicyRuleQuery.interval(destination)[0]; }
        public Tuple with(String source, String destination, int destinationPort) { return new Tuple(source, destination, protocol, destinationPort); }
    }

    /**
     * The evaluated device: enrolled device id and virtual-system context exactly as {@link Target} carries them ('' = no
     * virtual system), and for Palo Alto the firewall serial Panorama target selectors name ({@code null} = unknown). The
     * serial is compared locally and never returned.
     */
    public record Device(String deviceId, String context, String panSerial) {
        public Device {
            Objects.requireNonNull(deviceId, "deviceId");
            context = context == null ? "" : context;
        }
    }

    /**
     * The policy_target_binding rows of one package seen from one device: references bound to this device and context, to
     * this device in any context, and to any device. {@link #NONE} proves nothing.
     */
    public record Binding(Set<String> deviceRefs, Set<String> sameDeviceRefs, Set<String> policyRefs) {
        public static final Binding NONE = new Binding(Set.of(), Set.of(), Set.of());
        public Binding { deviceRefs = Set.copyOf(deviceRefs); sameDeviceRefs = Set.copyOf(sameDeviceRefs); policyRefs = Set.copyOf(policyRefs); }
        /** Only rows of {@code policyId} with a known reference count; a NULL reference (UNKNOWN) proves nothing. */
        public static Binding of(String policyId, Collection<PolicyCollectionRepository.TargetBinding> rows, Device device) {
            Set<String> mine = new HashSet<>(), same = new HashSet<>(), all = new HashSet<>();
            for (var row : rows) {
                if (!row.policyId().equals(policyId) || row.installOnRef() == null) continue;
                all.add(row.installOnRef());
                if (row.deviceId().equals(device.deviceId())) {
                    same.add(row.installOnRef());
                    if (row.context().equals(device.context())) mine.add(row.installOnRef());
                }
            }
            return new Binding(mine, same, all);
        }
    }

    /**
     * One security (access) evaluation. {@code verdict} is ALLOWED, DENIED or UNKNOWN; a verdict with {@code caveats} is
     * never plain ({@link #plain}). Rule maps carry the stored (unmasked) rule identity; projecting them for a viewer is
     * the API layer's job.
     */
    public record Verdict(String verdict, String reason, String detail, Map<String, Object> decidingRule,
            List<Map<String, Object>> undecidableRules, List<Map<String, Object>> layers, List<String> caveats, List<String> notes) {
        public Verdict {
            undecidableRules = List.copyOf(undecidableRules); layers = List.copyOf(layers);
            caveats = List.copyOf(new LinkedHashSet<>(caveats)); notes = List.copyOf(new LinkedHashSet<>(notes));
        }
        static Verdict unknown(String reason, String detail, List<String> notes) {
            return new Verdict(UNKNOWN, reason, detail, null, List.of(), List.of(), List.of(), notes);
        }
        public boolean plain() { return verdict.equals(ALLOWED) && caveats.isEmpty(); }
        public boolean decided() { return !verdict.equals(UNKNOWN); }
    }

    /**
     * One hop: NAT, then access in the vendor order of §5. {@code preNatAccess} is always the access evaluation on the
     * original tuple when it ran; {@code postNatAccess} only where §5 evaluates the translated tuple (Check Point with a
     * destination translation). {@code egressZone} is the zone Palo Alto security used (post-NAT).
     */
    public record Hop(String verdict, String reason, HopNatEvaluator.Nat nat, Verdict preNatAccess, Verdict postNatAccess,
            String egressZone, List<String> caveats, List<String> notes) {
        public Hop { caveats = List.copyOf(new LinkedHashSet<>(caveats)); notes = List.copyOf(new LinkedHashSet<>(notes)); }
        public boolean plain() { return verdict.equals(ALLOWED) && caveats.isEmpty(); }
    }

    /** Prepare once per (snapshot, device); every tuple then reuses the device view and the normalized rules. */
    public static Prepared prepare(PolicySnapshot snapshot, Device device, Binding binding) {
        return prepare(snapshot, device, binding, DEFAULT_WORK);
    }
    public static Prepared prepare(PolicySnapshot snapshot, Device device, Binding binding, int workLimit) {
        return new Prepared(snapshot.withPageSplitsMerged(), device, binding == null ? Binding.NONE : binding, workLimit);
    }

    public static Verdict security(PolicySnapshot snapshot, Device device, Binding binding, Tuple tuple, String ingressZone, String egressZone) {
        return prepare(snapshot, device, binding).security(tuple, ingressZone, egressZone);
    }

    /**
     * Check Point hop (§5, A0 §1): NAT first match; access on the pre-NAT tuple; when a destination (address or port)
     * translation matched, access also on the post-NAT tuple -- equal verdicts are taken, different verdicts make the hop
     * UNKNOWN ({@link #CP_ACCESS_NAT_ORDER_UNKNOWN}). An UNKNOWN NAT makes the hop UNKNOWN: a translation may exist.
     */
    public static Hop checkPoint(PolicySnapshot snapshot, Device device, Binding binding, Tuple tuple) {
        return prepare(snapshot, device, binding).checkPoint(tuple);
    }

    /**
     * Palo Alto hop (§5, A0 §4a): NAT first match with the ingress zone and the provisional egress zone (route lookup on
     * the original destination), then security on the pre-NAT addresses and port with the post-NAT egress zone --
     * {@code egressZoneFor} applied to the translated destination after a destination translation (the second route
     * lookup; {@code null} = unknown), the provisional zone otherwise.
     */
    public static Hop paloAlto(PolicySnapshot snapshot, Device device, Tuple tuple, String ingressZone, String provisionalEgressZone,
            String egressInterface, Function<String, String> egressZoneFor) {
        return prepare(snapshot, device, Binding.NONE).paloAlto(tuple, ingressZone, provisionalEgressZone, egressInterface, egressZoneFor);
    }

    public static final class Prepared {
        private final PolicySnapshot snapshot;
        private final Device device;
        private final Binding binding;
        private final int workLimit;
        private final String vendor;
        private final View view;
        private final PolicyLookup.Prepared lookup;
        private Prepared(PolicySnapshot snapshot, Device device, Binding binding, int workLimit) {
            this.snapshot = snapshot; this.device = device; this.binding = binding; this.workLimit = workLimit;
            this.vendor = snapshot.metadata().vendor();
            this.view = Set.of("CP", "PAN").contains(vendor) ? view(snapshot, device, binding) : null;
            this.lookup = view == null ? null : PolicyLookup.prepare(view.snapshot(), workLimit);
        }
        public PolicySnapshot snapshot() { return snapshot; }

        public Verdict security(Tuple tuple, String ingressZone, String egressZone) {
            List<String> notes = new ArrayList<>(), caveats = new ArrayList<>();
            if (vendor.equals("CP")) notes.add(CP_IMPLIED_RULES_NOT_MODELED);
            if (view == null) return Verdict.unknown(UNSUPPORTED_VENDOR, null, notes);
            String gate = targetGate(caveats);
            if (gate != null) return Verdict.unknown(gate, null, notes);
            if (tuple.service() == null) return Verdict.unknown(UNSUPPORTED_PROTOCOL, null, notes);
            boolean cp = vendor.equals("CP");
            var query = new PolicyLookup.Query(tuple.source(), tuple.destination(), tuple.service(),
                cp ? null : ingressZone, cp ? null : egressZone, cp ? DEVICE_PLACEMENT : null, null);
            Map<String, Object> body = lookup.evaluate(query).body();
            String verdict = (String) body.get("verdict"), detail = (String) body.get("lookupReason");
            @SuppressWarnings("unchecked") var deciding = (Map<String, Object>) body.get("decidingRule");
            @SuppressWarnings("unchecked") var layers = (List<Map<String, Object>>) body.get("layers");
            @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) body.get("undecidableRules");
            List<Map<String, Object>> undecidable = new ArrayList<>();
            for (var row : rows) {
                var copy = new LinkedHashMap<>(row);
                String reason = view.ruleReasons().get((String) row.get("ruleId"));
                if (reason != null) copy.put("pathReason", reason);
                undecidable.add(copy);
            }
            String reason = reason(detail);
            if (!cp && verdict.equals(UNKNOWN) && undecidable.isEmpty() && deciding == null
                    && (PAN_DEFAULT_DETAILS.contains(detail))) {
                return panDefault(ingressZone, egressZone, detail, caveats, notes);
            }
            Set<String> decidingIds = new HashSet<>();
            if (deciding != null) decidingIds.add((String) deciding.get("ruleId"));
            for (var layer : layers) if (layer.get("decidingRule") instanceof Map<?, ?> rule) decidingIds.add((String) rule.get("ruleId"));
            if (decidingIds.stream().anyMatch(view.appAssumed()::contains)) caveats.add(APP_ID_ASSUMED);
            if (!cp && !view.local() && deciding != null && PAN_POST.equals(deciding.get("sectionName"))) caveats.add(PAN_LOCAL_RULES_NOT_COLLECTED);
            return new Verdict(verdict, reason, detail, deciding, undecidable, layers, caveats, notes);
        }

        /** NAT first match for this device (see {@link HopNatEvaluator}). */
        public HopNatEvaluator.Nat nat(Tuple tuple, String ingressZone, String provisionalEgressZone, String egressInterface) {
            return HopNatEvaluator.evaluate(snapshot, device, binding, tuple, ingressZone, provisionalEgressZone, egressInterface, workLimit);
        }

        public Hop checkPoint(Tuple tuple) {
            if (!vendor.equals("CP")) return new Hop(UNKNOWN, UNSUPPORTED_VENDOR, null, null, null, null, List.of(), List.of());
            var nat = nat(tuple, null, null, null);
            Verdict pre = security(tuple, null, null);
            List<String> notes = new ArrayList<>(pre.notes()), caveats = new ArrayList<>(pre.caveats());
            notes.addAll(nat.notes());
            if (nat.status().equals(HopNatEvaluator.UNKNOWN))
                return new Hop(UNKNOWN, NAT_UNKNOWN, nat, pre, null, null, caveats, notes);
            if (!nat.destinationTranslated()) {
                if (nat.sourceTranslated()) notes.add(CP_SOURCE_NAT_ACCESS_ON_PRE_NAT);
                return new Hop(pre.verdict(), pre.reason(), nat, pre, null, null, caveats, notes);
            }
            notes.add(CP_ROUTE_AFTER_NAT_UNKNOWN);
            Verdict post = security(nat.translated(), null, null);
            caveats.addAll(post.caveats());
            if (!pre.verdict().equals(post.verdict()))
                return new Hop(UNKNOWN, CP_ACCESS_NAT_ORDER_UNKNOWN, nat, pre, post, null, caveats, notes);
            return new Hop(pre.verdict(), pre.reason(), nat, pre, post, null, caveats, notes);
        }

        public Hop paloAlto(Tuple tuple, String ingressZone, String provisionalEgressZone, String egressInterface,
                Function<String, String> egressZoneFor) {
            if (!vendor.equals("PAN")) return new Hop(UNKNOWN, UNSUPPORTED_VENDOR, null, null, null, null, List.of(), List.of());
            var nat = nat(tuple, ingressZone, provisionalEgressZone, egressInterface);
            List<String> notes = new ArrayList<>(nat.notes()), caveats = new ArrayList<>(nat.caveats());
            if (nat.status().equals(HopNatEvaluator.UNKNOWN))
                return new Hop(UNKNOWN, NAT_UNKNOWN, nat, null, null, null, caveats, notes);
            String egress = provisionalEgressZone;
            if (nat.destinationAddressTranslated()) egress = egressZoneFor == null ? null : egressZoneFor.apply(nat.translated().destination());
            if (egress == null || egress.isEmpty())
                return new Hop(UNKNOWN, POST_NAT_EGRESS_ZONE_UNKNOWN, nat, null, null, null, caveats, notes);
            if (nat.portTranslated()) notes.add(PAN_SECURITY_PRE_NAT_PORT);
            // Security matches the original (pre-NAT) addresses and the post-NAT zones; the port is translated only as the
            // packet leaves the firewall (A0 §4a), so the original port is matched too.
            Verdict security = security(tuple, ingressZone, egress);
            caveats.addAll(security.caveats()); notes.addAll(security.notes());
            return new Hop(security.verdict(), security.reason(), nat, security, null, egress, caveats, notes);
        }

        /** UNKNOWN reason when the snapshot is not proven to be this device's policy, else {@code null}. */
        private String targetGate(List<String> caveats) {
            var targets = snapshot.metadata().targets().stream()
                .filter(t -> t.deviceId().equals(device.deviceId()) && t.context().equals(device.context())).toList();
            if (targets.isEmpty()) return TARGET_CONTEXT_UNPROVEN;
            if (vendor.equals("PAN") && !view.local()) {
                if (targets.stream().anyMatch(t -> "OUT_OF_SYNC".equals(t.syncStatus()))) return STALE_OR_OUT_OF_SYNC_POLICY;
                if (targets.stream().anyMatch(t -> !"IN_SYNC".equals(t.syncStatus()))) caveats.add(POLICY_SYNC_UNKNOWN);
            }
            return null;
        }

        private Verdict panDefault(String ingressZone, String egressZone, String detail, List<String> caveats, List<String> notes) {
            if (!view.local()) return new Verdict(UNKNOWN, PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED, detail, null, List.of(), List.of(), caveats, notes);
            String type = ingressZone.equals(egressZone) ? "intrazone" : "interzone";
            List<Map<String, Object>> found = new ArrayList<>();
            String action = null;
            for (Section section : snapshot.sections()) {
                if (!PAN_LOCAL_DEFAULT.equals(section.name())) continue;
                for (Rule rule : section.rules()) {
                    if (!List.of(type).equals(rule.extras().get("rule-type"))) continue;
                    found.add(ruleView(section, rule)); action = rule.action();
                }
            }
            if (found.size() != 1) return new Verdict(UNKNOWN, PAN_DEFAULT_RULE_NOT_COLLECTED, detail, null, List.of(), List.of(), caveats, notes);
            String verdict = "allow".equals(action) ? ALLOWED
                : Set.of("deny", "drop", "reset-client", "reset-server", "reset-both").contains(action) ? DENIED : UNKNOWN;
            return new Verdict(verdict, PAN_DEFAULT_RULE_OVERRIDE, detail, found.get(0), List.of(), List.of(), caveats, notes);
        }
    }

    private static final String PAN_POST = "Post rules";
    private static final Set<String> PAN_DEFAULT_DETAILS = Set.of(
        "no rule matched; intrazone-default applies (allow by default, override not collected)",
        "no rule matched; interzone-default applies (deny by default, override not collected)");
    /** A placement no stored install-on reference equals: a CP rule whose install-on stays in the view is never bound. */
    private static final String DEVICE_PLACEMENT = "path-analysis:device";

    private static String reason(String detail) {
        if (detail == null) return LOOKUP_UNKNOWN;
        return switch (detail) {
            case "first matching rule" -> FIRST_MATCHING_RULE;
            case "all ordered layers allowed" -> ALL_ORDERED_LAYERS_ALLOWED;
            case "ordered layer results" -> ORDERED_LAYER_UNKNOWN;
            case "earlier rule could match" -> EARLIER_RULE_UNRESOLVED;
            case "implicit cleanup action not collected" -> CP_CLEANUP_NOT_COLLECTED;
            case "zone or target required" -> ZONE_REQUIRED;
            case "target context unproven" -> TARGET_CONTEXT_UNPROVEN;
            case "snapshot collection incomplete" -> SNAPSHOT_INCOMPLETE;
            case "analysis budget" -> ANALYSIS_BUDGET;
            default -> LOOKUP_UNKNOWN;
        };
    }

    static Map<String, Object> ruleView(Section section, Rule rule) {
        var view = new LinkedHashMap<String, Object>();
        view.put("ruleId", rule.id()); view.put("number", rule.number()); view.put("name", rule.name());
        view.put("sectionId", section.id()); view.put("sectionName", section.name()); view.put("action", rule.action());
        return view;
    }

    // ---- Device view -------------------------------------------------------------------------------------------------

    /** Whether a rule applies to the evaluated device. */
    enum Placement { APPLIES, SKIP, UNKNOWN }

    record View(PolicySnapshot snapshot, Map<String, String> ruleReasons, Set<String> appAssumed, boolean local) {}

    private static View view(PolicySnapshot snapshot, Device device, Binding binding) {
        var metadata = snapshot.metadata();
        boolean cp = metadata.vendor().equals("CP");
        boolean local = !cp && snapshot.sections().stream().anyMatch(s -> PAN_LOCAL_RULES.equals(s.name()));
        Map<String, PolicyObject> objects = new HashMap<>(snapshot.objects());
        String any = ref(metadata.id(), "path-analysis", "any");
        objects.put(any, new PolicyObject(any, "ANY", "any", List.of(), List.of(), "RESOLVED"));
        Map<String, String> reasons = new HashMap<>();
        Set<String> assumed = new HashSet<>();
        List<Section> sections = new ArrayList<>();
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(metadata, section)) { sections.add(section); continue; }
            List<Rule> rules = new ArrayList<>();
            for (Rule rule : section.rules()) {
                Placement placement = cp ? installOn(snapshot, rule.extras().get("install-on"), device, binding)
                    : panTarget(rule.extras(), device);
                if (placement == Placement.SKIP) { rules.add(copy(rule, false, rule.extras(), rule.service(), rule.application())); continue; }
                if (placement == Placement.UNKNOWN) {
                    reasons.put(rule.id(), cp ? INSTALL_ON_UNPROVEN : PAN_TARGET_UNPROVEN);
                    rules.add(rule); continue;
                }
                Map<String, List<String>> extras = new LinkedHashMap<>(rule.extras());
                if (cp) extras.put("install-on", List.of(any));
                else PAN_TARGET_KEYS.forEach(extras::remove);
                Cell service = rule.service(), application = rule.application();
                if (!cp && appDefault(metadata, objects, service)) {
                    var ports = defaultPorts(objects, application);
                    if (ports == null) reasons.put(rule.id(), APP_DEFAULT_PORTS_NOT_STORED);
                    else {
                        String id = ref(metadata.id(), "path-analysis", "application-default", rule.id());
                        List<String> values = new ArrayList<>();
                        ports.forEach((protocol, list) -> values.add("protocol/" + protocol + "/port: " + String.join(",", list)));
                        objects.put(id, new PolicyObject(id, "application-default", "service", List.of(), values, "RESOLVED"));
                        service = new Cell(List.of(id), false); application = new Cell(List.of(any), false);
                        assumed.add(rule.id());
                    }
                }
                rules.add(copy(rule, rule.enabled(), extras, service, application));
            }
            sections.add(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules));
        }
        return new View(new PolicySnapshot(metadata, sections, objects, snapshot.failures()), Map.copyOf(reasons), Set.copyOf(assumed), local);
    }

    private static Rule copy(Rule r, Boolean enabled, Map<String, List<String>> extras, Cell service, Cell application) {
        return new Rule(r.id(), r.uuid(), r.number(), r.name(), enabled, r.source(), r.destination(), service, application,
            r.action(), r.log(), r.comment(), extras, r.hitCounts());
    }

    /**
     * Check Point install-on for the device (G7). APPLIES: a reference is any, bound to this device and context, or
     * "Policy Targets" while the device is a package target. SKIP: every reference is a plain gateway object that the
     * binding ties to other devices and to no context of this device. Anything else (absent install-on, unresolved or
     * group objects, unbound gateways, no binding) is UNKNOWN.
     */
    static Placement installOn(PolicySnapshot snapshot, List<String> refs, Device device, Binding binding) {
        if (refs == null || refs.isEmpty()) return Placement.UNKNOWN;
        boolean unknown = false;
        for (String id : refs) {
            var object = snapshot.objects().get(id);
            if (object == null) { unknown = true; continue; }
            if (object.type().equals("any") && object.status().equals("RESOLVED")) return Placement.APPLIES;
            if (binding.deviceRefs().contains(id)) return Placement.APPLIES;
            if (policyTargets(object)) {
                if (packageTarget(snapshot, device)) return Placement.APPLIES;
                unknown = true; continue;
            }
            boolean otherDevice = object.status().equals("RESOLVED") && object.type().equals("gateway") && object.members().isEmpty()
                && binding.policyRefs().contains(id) && !binding.sameDeviceRefs().contains(id);
            if (!otherDevice) unknown = true;
        }
        return unknown ? Placement.UNKNOWN : Placement.SKIP;
    }

    /**
     * The vendor's "Policy Targets" install-on entry: objects-dictionary {@code "name": "Policy Targets"}, {@code "type":
     * "Global"} (A0 §3b: recognised by dictionary entry, never by uid), stored by the mapper with its native type.
     */
    static boolean policyTargets(PolicyObject object) {
        return "Policy Targets".equals(object.name()) && object.values().contains("native-type: Global");
    }
    static boolean packageTarget(PolicySnapshot snapshot, Device device) {
        return snapshot.metadata().targets().stream().anyMatch(t -> t.deviceId().equals(device.deviceId()) && t.context().equals(device.context()));
    }

    static final List<String> PAN_TARGET_KEYS = List.of("target/negate", "target-selectors", "target-vsys", "target-tags", "target-unrecognized");

    /** Palo Alto target / negate for the device (G8, A0 §4d). */
    static Placement panTarget(Map<String, List<String>> extras, Device device) {
        if (extras.containsKey("target-tags") || extras.containsKey("target-vsys") || extras.containsKey("target-unrecognized")) return Placement.UNKNOWN;
        var negateValue = extras.getOrDefault("target/negate", List.of("no"));
        if (!negateValue.equals(List.of("no")) && !negateValue.equals(List.of("yes"))) return Placement.UNKNOWN;
        boolean negate = negateValue.equals(List.of("yes"));
        var selected = extras.getOrDefault("target-selectors", List.of());
        if (selected.isEmpty()) return negate ? Placement.SKIP : Placement.APPLIES;
        if (device.panSerial() == null || device.panSerial().isEmpty()) return Placement.UNKNOWN;
        boolean listed = selected.contains(device.panSerial());
        return listed != negate ? Placement.APPLIES : Placement.SKIP;
    }

    /** The service cell is exactly the mapper's built-in application-default service. */
    private static boolean appDefault(Metadata metadata, Map<String, PolicyObject> objects, Cell service) {
        if (service.negated() || service.refs().size() != 1) return false;
        String id = service.refs().get(0);
        var object = objects.get(id);
        return id.equals(ref(metadata.id(), "service", "application-default")) && object != null
            && object.type().equals("service") && object.values().isEmpty() && object.members().isEmpty();
    }

    /**
     * The default ports of every application in the cell, by protocol, or {@code null} when any one is not stored: an
     * application (or application-group member) must be a resolved stored object with at least one {@code default/port}
     * value, each {@code tcp|udp/<port>[-<port>][,...]}. Application any is UNKNOWN (A0 §6).
     */
    static Map<String, List<String>> defaultPorts(Map<String, PolicyObject> objects, Cell applications) {
        if (applications.negated() || applications.refs().isEmpty()) return null;
        Map<String, List<String>> ports = new TreeMap<>();
        for (String id : applications.refs()) if (!collectPorts(objects, id, ports, 0)) return null;
        return ports.isEmpty() ? null : ports;
    }
    private static boolean collectPorts(Map<String, PolicyObject> objects, String id, Map<String, List<String>> ports, int depth) {
        var object = objects.get(id);
        if (object == null || depth > 32 || !object.status().equals("RESOLVED")) return false;
        if (object.type().equals("application-group")) {
            if (object.members().isEmpty()) return false;
            for (String member : object.members()) if (!collectPorts(objects, member, ports, depth + 1)) return false;
            return true;
        }
        if (!object.type().equals("application") || !object.members().isEmpty()) return false;
        boolean any = false;
        for (String value : object.values()) {
            if (!value.startsWith("default/port: ")) continue;
            String spec = value.substring("default/port: ".length()).trim();
            int slash = spec.indexOf('/');
            if (slash < 0) return false;
            String protocol = spec.substring(0, slash);
            if (!Set.of("tcp", "udp").contains(protocol)) return false;
            for (String port : spec.substring(slash + 1).split(",", -1)) {
                String part = port.trim();
                if (!part.matches("[0-9]{1,5}(?:-[0-9]{1,5})?")) return false;
                ports.computeIfAbsent(protocol, p -> new ArrayList<>()).add(part);
            }
            any = true;
        }
        return any;
    }
}
