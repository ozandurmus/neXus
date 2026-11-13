package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;

/** Shared stored-policy normalization; identifiers remain opaque. */
final class PolicyNormalization {
    private PolicyNormalization() {}
    record Range(long start, long end) {}
    /**
     * One resolved dimension: {@code any}, merged ranges per family, and opaque {@code tokens}. Tokens are compared by
     * identity only, never interpreted: on the service dimension the single token {@link #APPLICATION_DEFAULT} (Palo Alto
     * {@code application-default}, see {@link Resolver#cell}); on the application dimension the opaque references of the
     * applications of an application-default rule ({@link Resolver#applications}), and of the content-provided
     * applications a Palo Alto rule with any other service names directly ({@link Resolver#contentApplication}). A selector
     * with tokens is never disjoint from anything ({@link #disjoint(Selector, Selector, Budget)}).
     */
    record Selector(boolean any, Map<String, List<Range>> ranges, Set<String> tokens) {
        Selector { tokens = Set.copyOf(tokens); }
        Selector(boolean any, Map<String, List<Range>> ranges) { this(any, ranges, Set.of()); }
        /** The service dimension of a Palo Alto rule whose service is {@code application-default}. */
        boolean applicationDefault() { return tokens.contains(APPLICATION_DEFAULT); }
    }
    /**
     * Palo Alto {@code application-default} on the service dimension: an opaque token whose meaning depends on the rule's
     * application set. PATH_ANALYSIS_VENDOR_SEMANTICS.md section 6 (FROZEN), quoting the vendor (PA-CSR, PAN-OS 11.1):
     * application-default is used "to ensure that any applications that the rule allows are allowed only on their
     * standard ports", and (PA-AD) it "prevents security evasion by only allowing applications to run on their standard
     * ports". The same section leaves UNKNOWN "per-application default ports (not collected); matching before App-ID
     * identifies the app; interaction with application any". Shadowing therefore relies on identity only: a rule matches
     * each of its applications on that application's own standard ports -- a fixed but unknown, non-empty port set per
     * application -- and application any with application-default is a fixed but unknown match of its own, not read as
     * "every application's standard ports". No port list is invented. The D-5 "App-ID assumed" caveat is a path-analysis
     * concern ({@code HopPolicyEvaluator}) and is not applied here.
     */
    static final String APPLICATION_DEFAULT = "application-default";
    record Match(Rule rule, List<Selector> selectors, Map<String, Set<String>> placements, String problem) {}
    static final class Unknown extends RuntimeException {
        Unknown(String reason) { super(reason, null, false, false); }
    }
    /**
     * Work counter: one unit per resolution, range or comparison step. {@code left} is the shared total; {@link #rule}
     * opens a per-rule window so one expensive rule exhausts only itself, never every later rule.
     */
    static final class Budget {
        final int[] left;
        private final int[] window, sideLeft;
        Budget(int limit) { this(new int[]{limit}, new int[]{Integer.MAX_VALUE}, new int[]{limit}); }
        private Budget(int[] left, int[] window, int[] sideLeft) { this.left = left; this.window = window; this.sideLeft = sideLeft; }
        void rule(int limit) { window[0] = limit; }
        void spend() { if (--left[0] < 0 | --window[0] < 0) throw new Unknown("analysis budget"); }
        /**
         * The same per-rule window over a separate snapshot-wide pool of the same size, for partial-prior work (resolving
         * what an unresolvable rule's selectors do resolve, and the later disjointness checks). That work only ever turns
         * an "earlier rule has unsupported constraints" UNKNOWN into NOT_SHADOWED, so it must never take budget another
         * rule's verdict needed: running out of it leaves the rule exactly as before (still UNKNOWN for that reason).
         */
        Budget side() { return new Budget(sideLeft, window, sideLeft); }
    }
    /** Section IDs hash the layer UID recursively; the UID cannot be recovered from a snapshot. */
    static final class CpLayers {
        private record Layer(int index, String label, int lastNumber) {}
        private final Map<String, Layer> parents = new HashMap<>();
        private int next;
        int advance(Section section, Budget budget) {
            if (section.parentRuleId() != null && section.parentRuleId().isBlank())
                throw new Unknown("multiple ordered layers not evaluated");
            String label = null;
            boolean missingLabel = false;
            for (Rule rule : section.rules()) {
                budget.spend();
                var labels = rule.extras().getOrDefault("layer-name", List.of());
                if (labels.size() > 1) throw new Unknown("multiple ordered layers not evaluated");
                if (labels.isEmpty() || labels.get(0).isBlank()) {
                    missingLabel = true;
                    continue;
                }
                if (label != null && !label.equals(labels.get(0))) throw new Unknown("multiple ordered layers not evaluated");
                label = labels.get(0);
            }
            if (missingLabel && label != null) throw new Unknown("multiple ordered layers not evaluated");
            Layer previous = parents.get(section.parentRuleId());
            boolean split = previous != null && label != null && previous.label() != null && !label.equals(previous.label());
            if (previous != null && (label == null || previous.label() == null || split && section.parentRuleId() != null))
                throw new Unknown("multiple ordered layers not evaluated");
            // Labels only split runs; continuation requires strictly increasing rule numbers, never label equality alone.
            int last = previous == null || split ? 0 : previous.lastNumber();
            for (Rule rule : section.rules()) {
                budget.spend();
                if (rule.number() <= last) throw new Unknown("multiple ordered layers not evaluated");
                last = rule.number();
            }
            int index = previous == null || split ? next++ : previous.index();
            parents.put(section.parentRuleId(), new Layer(index, label, last));
            return index;
        }
    }
    /**
     * Which earlier rules a section's rules are compared with. {@link #advance} returns the comparison state the section
     * continues: the previous section's state where the stored order is proven to continue it, a fresh state where a new
     * evaluation scope starts. {@link #unproven} marks a section whose place in the evaluation order is not proven.
     * <p>Check Point access rules are scoped by layer identity, not by section identity (PATH_ANALYSIS_VENDOR_SEMANTICS.md
     * 2b, 2c): sections only divide a layer visually, and an inline layer is an independent sub-policy entered only through
     * its parent "Apply Layer" rule, after which no later rule of the parent layer is matched. An inline layer's sections
     * get a state of their own; when the inline layer ends, the parent layer's state resumes. Layer identity rests on the
     * mapper's opaque links -- the child section names as parent the last rule of the section just before it, and that
     * rule carries an inline-layer reference -- plus {@link CpLayers}' label and strictly increasing rule-number checks
     * (enforcement order inside a layer stays the stored order only where the numbers increase). Anything else -- a
     * broken parent link, a layer seen again after it closed, label or number anomalies -- is unproven, as before.
     */
    static final class EvaluationScope<S> {
        private static final class Frame<S> {
            final int layer; final S state; boolean unproven;
            Frame(int layer, S state) { this.layer = layer; this.state = state; }
        }
        private final Metadata metadata;
        private final java.util.function.Supplier<S> fresh;
        private final Set<Integer> layersSeen = new HashSet<>();
        private final CpLayers cpLayers = new CpLayers();
        /** Open Check Point layers, innermost first: one ordered layer and the inline layers entered from it. */
        private final Deque<Frame<S>> open = new ArrayDeque<>();
        private final Set<String> sectionIds = new HashSet<>();
        private Section previous;
        private S current;
        private int lastPhase = -1;
        private boolean orderKnown = true;
        boolean unproven;
        EvaluationScope(Metadata metadata, java.util.function.Supplier<S> fresh) { this.metadata = metadata; this.fresh = fresh; }
        S advance(Section section) {
            // A section without rules evaluates nothing and carries no layer label, so it neither continues nor breaks the
            // scope (2026-11-03: empty CP sections made about 7,200 later rules read "earlier sections not analysed").
            if (section.rules().isEmpty()) { if (current == null) current = fresh.get(); return current; }
            S state;
            if (metadata.vendor().equals("CP") && "CP access layer".equals(section.source())) state = checkPoint(section);
            else {
                boolean joined = false;
                if (metadata.vendor().equals("PAN")) {
                    int phase = panPhase(section);
                    boolean ordered = phase >= 0 && phase >= lastPhase && sectionIds.add(section.id());
                    joined = previous != null && orderKnown && ordered;
                    if (previous != null && !joined) unproven = true;
                    orderKnown &= ordered;
                    lastPhase = phase;
                } else {
                    unproven = previous != null;
                }
                open.clear();
                state = joined && !unproven && current != null ? current : fresh.get();
            }
            previous = section; current = state;
            return state;
        }
        private S checkPoint(Section section) {
            int layer;
            try { layer = cpLayers.advance(section, new Budget(Integer.MAX_VALUE)); }
            catch (Unknown unknown) { open.clear(); unproven = true; return fresh.get(); }
            Frame<S> frame = null;
            for (var candidate : open) if (candidate.layer == layer) { frame = candidate; break; }
            if (frame != null) {
                // The same layer continues, or resumes after the inline layers entered from it ended.
                while (open.peek() != frame) open.pop();
            } else {
                boolean entered = section.parentRuleId() != null && entersInlineLayer(section.parentRuleId());
                boolean seen = !layersSeen.add(layer);
                if (seen || section.parentRuleId() != null && !entered) {
                    sectionIds.add(section.id());
                    open.clear(); unproven = true; return fresh.get();
                }
                // A new ordered layer replaces the open chain; an inline layer opens on top of its parent's layer.
                if (!entered) open.clear();
                frame = new Frame<>(layer, fresh.get());
                open.push(frame);
            }
            if (!sectionIds.add(section.id())) frame.unproven = true;
            unproven = frame.unproven;
            return frame.state;
        }
        /** The previous section ends with the named parent rule, and that rule applies an inline layer. */
        private boolean entersInlineLayer(String parentRuleId) {
            if (previous == null || !"CP access layer".equals(previous.source()) || previous.rules().isEmpty()) return false;
            Rule last = previous.rules().get(previous.rules().size() - 1);
            return parentRuleId.equals(last.id()) && !last.extras().getOrDefault("inline-layer", List.of()).isEmpty();
        }
        int panPhase(Section section) {
            if (section.parentRuleId() != null || metadata.id() == null || metadata.id().isBlank()
                    || metadata.sourceId() == null || metadata.sourceId().isBlank()
                    || metadata.containerId() == null || metadata.containerId().isBlank()
                    || section.source() == null || section.source().isBlank()) return -1;
            // Verify the mapper's opaque section identity; display labels do not establish phase or lineage.
            var phases = List.of("pre-rulebase", "rulebase", "post-rulebase");
            for (int i = 0; i < phases.size(); i++)
                if (ref(metadata.id(), section.source(), phases.get(i)).equals(section.id())) return i;
            return -1;
        }
    }
    static Match normalize(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver) {
        return normalize(snapshot, section, rule, resolver, false);
    }
    static Match normalize(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver, boolean lookup) {
        return normalize(snapshot, section, rule, resolver, lookup, false, false);
    }
    /**
     * The full match of a Palo Alto intrazone or interzone rule whose only obstacle to {@link #normalize} is that rule
     * type, or {@code null}. Only {@code PolicyHygiene} reads it, and only as a rule an earlier universal rule may cover
     * (see {@link #zonesCover}); it is never an earlier rule, a lookup match or a partial prior.
     */
    static Match typed(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver) { return typed(snapshot, section, rule, resolver, false); }
    static Match typed(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver, boolean upperBound) {
        if (!snapshot.metadata().vendor().equals("PAN")) return null;
        var match = normalize(snapshot, section, rule, resolver, false, true, upperBound);
        return match.problem() == null ? match : null;
    }
    /**
     * The upper-bound match of a rule: its full match with every narrowing constraint dropped (time, schedule, VPN,
     * content, URL category, Palo Alto source-user, and a Check Point time column that was not collected), so whatever
     * the rule really matches lies inside it. Every selector must still resolve exactly as for {@link #normalize}: no
     * bound stands in for a selector (in particular the Check Point access-role network bound,
     * {@link Resolver#accessRoleBound}, is not used, so an access-role cell leaves the rule without an upper-bound
     * match). A match from here may only ever be read as "matches at most this": it proves that a rule cannot match a
     * packet outside it, never that the rule matches a packet inside it. The policy hygiene legacy mode and any
     * "upper-bound match" consumer (zone matrix) read it; lookup and path analysis do not.
     */
    static Match upperBound(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver) {
        return normalize(snapshot, section, rule, resolver, false, false, true);
    }
    /** Constraints the upper-bound match drops: each one only narrows what the rule matches ({@link #partial}). */
    static final Set<String> UPPER_BOUND_DROPPED = Set.of("time", "schedule", "vpn", "content", "category", "source-user", "time-uncollected");
    private static Match normalize(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver, boolean lookup, boolean ruleTypes, boolean upperBound) {
        try {
            if (!Set.of("CP", "PAN").contains(snapshot.metadata().vendor()) || PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section))
                throw new Unknown("unsupported rulebase");
            Selector source = resolver.cell(rule.source(), "address"), destination = resolver.cell(rule.destination(), "address"),
                service = resolver.cell(rule.service(), "service");
            List<Selector> cells = List.of(source, destination, service, snapshot.metadata().vendor().equals("CP") && rule.application().refs().isEmpty()
                && !rule.application().negated() ? new Selector(true, Map.of()) : service.applicationDefault()
                    ? resolver.applications(rule.application()) : resolver.cell(rule.application(), "application"));
            if (snapshot.metadata().vendor().equals("PAN") && (rule.extras().getOrDefault("from", List.of()).isEmpty()
                    || rule.extras().getOrDefault("to", List.of()).isEmpty())) throw new Unknown("zone evidence not collected");
            if (snapshot.metadata().vendor().equals("CP") && rule.extras().getOrDefault("install-on", List.of()).isEmpty())
                throw new Unknown("install-on evidence not collected");
            if (rule.enabled() == null) throw new Unknown("enabled state is unknown");
            // A matched "Apply Layer" rule ends matching in its layer (PATH_ANALYSIS_VENDOR_SEMANTICS.md 2b), so for shadowing
            // it is a terminal rule of the parent layer; it must name exactly one inline layer. The lookup descends into it.
            boolean applyLayer = snapshot.metadata().vendor().equals("CP") && rule.action().equals("Apply Layer")
                && (lookup || rule.extras().getOrDefault("inline-layer", List.of()).size() == 1);
            if (!applyLayer && !Set.of("Accept", "Drop", "Reject", "allow", "deny", "drop", "reset-client", "reset-server", "reset-both").contains(rule.action()))
                throw new Unknown("non-terminal or unknown action");
            for (var entry : rule.extras().entrySet()) {
                String key = entry.getKey(); var values = entry.getValue();
                if (applyLayer && key.equals("inline-layer")) continue;
                if (upperBound && UPPER_BOUND_DROPPED.contains(key)) continue;
                if (Set.of("time", "schedule", "vpn", "content").contains(key)) {
                    for (String id : values) {
                        resolver.budget.spend(); var object = snapshot.objects().get(id);
                        if (object == null || !object.status().equals("RESOLVED") || !object.type().equals("any")) throw new Unknown("time or content constraint");
                    }
                } else if (Set.of("source-user", "category").contains(key) && !values.equals(List.of("any"))) throw new Unknown("user or category constraint");
                else if (key.equals("rule-type") && !values.equals(List.of("universal"))
                        && !(ruleTypes && (values.equals(List.of("intrazone")) || values.equals(List.of("interzone"))))) throw new Unknown("zone rule type constraint");
                else if (key.equals("install-on")) {
                    for (String id : values) {
                        resolver.budget.spend(); var object = snapshot.objects().get(id);
                        // Shadowing reads install-on only as a placement key: its opaque object references, compared by exact
                        // set equality, never by meaning. A stored reference of any status (e.g. "Policy Targets", native type
                        // Global, stored UNSUPPORTED) names the same target set as the same reference on another rule. The
                        // lookup binds install-on to a queried target and still needs the resolved object.
                        if (object == null || !(object.status().equals("RESOLVED") || !lookup && OPAQUE_PLACEMENTS.contains(object.status())))
                            throw new Unknown("unresolved install-on selector");
                    }
                } else if (!Set.of("from", "to", "install-on", "source-user", "category", "rule-type", "tag", "layer-name", "last-modified", "last-modifier", "log-setting").contains(key)
                        && !key.startsWith("track-") && !key.startsWith("profile-setting/") && !untargeted(snapshot, rule, key)) throw new Unknown("unsupported rule constraint");
            }
            if (!upperBound && snapshot.metadata().vendor().equals("CP") && !rule.extras().containsKey("time")) throw new Unknown("time evidence not collected");
            Map<String, Set<String>> placements = new HashMap<>();
            for (String key : List.of("from", "to", "install-on")) {
                Set<String> values = new HashSet<>();
                for (String value : rule.extras().getOrDefault(key, List.of())) { resolver.budget.spend(); values.add(value); }
                placements.put(key, Set.copyOf(values));
            }
            return new Match(rule, cells, placements, null);
        } catch (Unknown problem) {
            if (problem.getMessage().equals("analysis budget")) throw problem;
            return new Match(rule, List.of(), Map.of(), problem.getMessage());
        }
    }
    /** Install-on object statuses shadowing accepts as opaque placement tokens (see {@link #normalize}). */
    static final Set<String> OPAQUE_PLACEMENTS = Set.of("UNSUPPORTED", "UNRESOLVED");
    /**
     * An enabled (or possibly enabled) rule without a full match, kept as what did resolve: a {@code null} selector is a
     * dimension that did not resolve, an empty placement set a placement that is not known. A partial prior never covers
     * anything; it only lets a later rule be proven NOT_SHADOWED when one of its resolved dimensions is disjoint from
     * the later rule's ({@link #disjoint(Partial, Match, Budget)}, {@link #disjoint(Map, Map, Map)}).
     */
    record Partial(Rule rule, List<Selector> selectors, Map<String, Set<String>> placements) {}
    /** Constraints that are one more condition a packet must also meet: they can only narrow the rule's match. */
    private static final Set<String> NARROWING = Set.of("time", "schedule", "vpn", "content", "source-user", "category");
    /** Fields that say what happens after a match (logging, tagging, security profiles, provenance), never what matches. */
    /**
     * A Palo Alto rule whose stored target is only {@code <negate>no</negate>} selects no device, tag or vsys, so it is added
     * to every firewall of its device group (PATH_ANALYSIS_VENDOR_SEMANTICS.md 4d: "If you do not select firewalls to target,
     * the rule is added to all of the (unchecked) firewalls in the device group") -- the same as a rule without a target.
     * 10,600 rules on the estate carry exactly this (2026-11-04); any other target shape stays unsupported.
     */
    static boolean untargeted(PolicySnapshot snapshot, Rule rule, String key) {
        return key.equals("target/negate") && "PAN".equals(snapshot.metadata().vendor()) && rule.extras().get(key).equals(List.of("no"))
            && TARGET_SHAPES.stream().noneMatch(rule.extras()::containsKey);
    }
    private static final Set<String> TARGET_SHAPES = Set.of("target-selectors", "target-tags", "target-vsys", "target-unrecognized");
    /**
     * A Palo Alto Panorama target field that can only restrict the firewalls a rule is pushed to, never widen what it
     * matches on a firewall (PATH_ANALYSIS_VENDOR_SEMANTICS.md 4d, FROZEN; PA-TGT, PA-SUB): with no firewall selected the
     * rule goes to every firewall of the device group; selected devices and tags restrict it to those; negate pushes it
     * "to all managed firewalls associated with the device group except for the selected device(s) and tag(s)"; negate
     * with nothing selected adds it to no firewall. Every form is therefore a subset of the device group's firewalls, and
     * on each firewall that has the rule it matches exactly its selectors. Only {@link #partial} reads this, as one more
     * narrowing condition: a targeted rule is still no full match, so it never covers a later rule (which firewalls it
     * reaches is not compared). The device entries restricted to virtual systems ({@code target-vsys}, a shape known from
     * the repository parser only) select fewer contexts still. {@code target-unrecognized} (a shape nobody parsed) and a
     * negate value other than yes or no stay of unknown meaning: such a rule is no partial prior.
     */
    static boolean targetNarrows(PolicySnapshot snapshot, Rule rule, String key) {
        if (!"PAN".equals(snapshot.metadata().vendor())) return false;
        if (key.equals("target/negate")) return rule.extras().get(key).equals(List.of("yes")) || rule.extras().get(key).equals(List.of("no"));
        return Set.of("target-selectors", "target-tags", "target-vsys").contains(key);
    }
    private static final Set<String> INERT = Set.of("tag", "layer-name", "last-modified", "last-modifier", "log-setting");
    /**
     * The partial prior of a rule {@link #normalize} could not fully resolve, or {@code null} when nothing of it may be
     * relied on (the rule then stays an earlier unknown, as before). Fail-closed by construction:
     * <ul>
     * <li>Each selector is resolved on its own with the same resolver; a negated, empty, unresolved, dynamic or otherwise
     * unsupported cell is unresolved, exactly as {@link Resolver#cell} decides for a full match.</li>
     * <li>A non-selector constraint -- time, schedule, VPN, content, user, URL category -- is one more condition a packet
     * must also meet before the rule matches. Whatever its value, resolved or not, it can only restrict the match, never
     * widen it, so whatever the rule matches still lies inside its resolved selectors and a dimension disjoint from a
     * later rule still proves that no packet matches both. The same holds for an unknown or non-terminal action and an
     * unknown enabled state: they change what the rule does with a match or whether it is active, not what it matches.</li>
     * <li>A Palo Alto Panorama target (devices, tags, virtual systems, negate yes or no; {@link #targetNarrows}) only
     * restricts which firewalls of the device group get the rule, so on every firewall the rule matches within its
     * selectors or not at all: the same kind of narrowing condition. It is still an "unsupported rule constraint" for
     * {@link #normalize}, so a targeted rule never covers.</li>
     * <li>A field whose meaning is not known (anything else {@link #normalize} calls an "unsupported rule constraint",
     * e.g. a target shape nobody parsed) might widen or invert the match, so such a rule is no partial prior at all.</li>
     * <li>A Check Point source naming an access role is resolved, where it can be, to an upper bound of what it matches:
     * the role's networks ({@link Resolver#accessRoleBound}). The identity the role also requires only narrows the match
     * further, so the bound may prove disjointness and nothing else; a partial prior never covers, so an access-role rule
     * still covers nothing.</li>
     * <li>A Palo Alto zone rule type other than universal changes how the from/to zones apply, so those zones are not
     * used; missing zone or install-on evidence is an unknown placement. A Check Point rule whose time column was not
     * collected is an incompletely collected rule record and is not used. Rules outside the security rulebase are not
     * used.</li>
     * </ul>
     */
    static Partial partial(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver) {
        String vendor = snapshot.metadata().vendor();
        if (!Set.of("CP", "PAN").contains(vendor) || PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) return null;
        boolean cp = vendor.equals("CP"), zones = true;
        if (cp && !rule.extras().containsKey("time")) return null;
        for (var entry : rule.extras().entrySet()) {
            String key = entry.getKey();
            resolver.budget.spend();
            if (NARROWING.contains(key) || INERT.contains(key) || Set.of("from", "to", "install-on").contains(key)
                    || key.startsWith("track-") || key.startsWith("profile-setting/") || untargeted(snapshot, rule, key)
                    || targetNarrows(snapshot, rule, key)) continue;
            if (key.equals("rule-type")) { zones &= entry.getValue().equals(List.of("universal")); continue; }
            // The inline layer an "Apply Layer" rule hands its match to; it is no condition on the match itself.
            if (key.equals("inline-layer") && cp && rule.action().equals("Apply Layer")) continue;
            return null;
        }
        var cells = List.of(rule.source(), rule.destination(), rule.service(), rule.application());
        var families = List.of("address", "address", "service", "application");
        Selector[] selectors = new Selector[4]; boolean resolved = false;
        for (int i = 0; i < 4; i++) {
            var cell = cells.get(i);
            try {
                selectors[i] = i == 3 && cp && cell.refs().isEmpty() && !cell.negated() ? new Selector(true, Map.of())
                    : i == 3 && selectors[2] != null && selectors[2].applicationDefault() ? resolver.applications(cell) : resolver.cell(cell, families.get(i));
                resolved |= !selectors[i].any();
            } catch (Unknown unresolved) {
                if (unresolved.getMessage().equals("analysis budget")) throw unresolved;
                // A Check Point source naming an access role: the role's networks bound it (Resolver#accessRoleBound).
                if (i == 0 && cp) try {
                    var bound = resolver.accessRoleBound(cell);
                    if (!bound.any()) { selectors[0] = bound; resolved = true; }
                } catch (Unknown none) {
                    if (none.getMessage().equals("analysis budget")) throw none;
                }
            }
        }
        Map<String, Set<String>> placements = new HashMap<>();
        for (String key : List.of("from", "to", "install-on")) {
            Set<String> values = new HashSet<>();
            if (zones || key.equals("install-on"))
                for (String value : rule.extras().getOrDefault(key, List.of())) { resolver.budget.spend(); values.add(value); }
            placements.put(key, Set.copyOf(values));
            resolved |= !values.isEmpty();
        }
        // Nothing that could ever prove disjointness resolved: the same as an earlier unknown, and cheaper.
        return resolved ? new Partial(rule, Collections.unmodifiableList(Arrays.asList(selectors)), Map.copyOf(placements)) : null;
    }
    /**
     * One resolved selector of the prior is disjoint from the same selector of the match. Together with disjoint
     * placements ({@link #disjoint(Map, Map, Map)}, tested and remembered separately by the caller) this is the whole
     * test that no packet can match both: a match is a product of its dimensions, so one empty intersection empties the
     * whole. An unresolved dimension proves nothing.
     */
    static boolean disjoint(Partial prior, Match match, Budget budget) {
        for (int i = 0; i < 4; i++) {
            var selector = prior.selectors().get(i);
            if (selector != null && disjoint(selector, match.selectors().get(i), budget)) return true;
        }
        return false;
    }
    /**
     * Two resolved selectors share no value: neither is any, and no range of one family overlaps a range of the same
     * family in the other (ranges are merged and sorted by {@link Resolver#union}). Families never overlap each other:
     * a TCP port is no UDP port, and resolved addresses are IPv4 ranges only.
     * <p>A selector with tokens is never disjoint from anything. Service: application-default against explicit ports is
     * not disjoint (the default ports are unknown), and application-default against application-default is disjoint
     * only when the application dimensions are -- which that dimension then proves on its own. Application: opaque
     * application references are never proven disjoint (two names need not be two disjoint traffic sets; matching before
     * App-ID identifies the application is UNKNOWN, PATH_ANALYSIS_VENDOR_SEMANTICS.md section 6).
     */
    static boolean disjoint(Selector a, Selector b, Budget budget) {
        if (a.any() || b.any()) return false;
        if (!a.tokens().isEmpty() || !b.tokens().isEmpty()) return false;
        // Families of one protocol meet when their source ports and their values both overlap (a plain family has every
        // source port); families of different protocols never meet.
        for (var mine : a.ranges().entrySet()) for (var theirs : b.ranges().entrySet()) {
            if (!protocol(mine.getKey()).equals(protocol(theirs.getKey()))) continue;
            if (overlap(sources(mine.getKey()), sources(theirs.getKey()), budget) && overlap(mine.getValue(), theirs.getValue(), budget)) return false;
        }
        return true;
    }
    private static boolean overlap(List<Range> mine, List<Range> theirs, Budget budget) {
        for (int i = 0, j = 0; i < mine.size() && j < theirs.size(); ) {
            budget.spend();
            Range x = mine.get(i), y = theirs.get(j);
            if (x.end() < y.start()) i++;
            else if (y.end() < x.start()) j++;
            else return true;
        }
        return false;
    }
    /** The two source-port fields of a Palo Alto service object (shadowing resolver only). */
    static final Set<String> SOURCE_PORT_FIELDS = Set.of("protocol/tcp/source-port", "protocol/udp/source-port");
    static final long IPV4_MAX = 0xFFFFFFFFL;
    private static final List<Range> EVERY_PORT = List.of(new Range(0, 65535));
    /**
     * The family of a TCP or UDP service restricted to source ports: {@code tcp|s=a-b,c-d} with the merged, sorted source
     * ranges, so equal restrictions share one family; its ranges are the destination ports. A plain {@code tcp} or
     * {@code udp} family has every source port.
     */
    static String sourceFamily(String protocol, List<Range> sources) {
        StringBuilder key = new StringBuilder(protocol).append("|s=");
        for (int i = 0; i < sources.size(); i++) key.append(i == 0 ? "" : ",").append(sources.get(i).start()).append('-').append(sources.get(i).end());
        return key.toString();
    }
    /** The protocol of a family: the family itself, or the part before a source-port restriction. */
    static String protocol(String family) { int at = family.indexOf("|s="); return at < 0 ? family : family.substring(0, at); }
    /** The source ports of a family: every port for a plain TCP/UDP family; for any other plain family, one whole range. */
    static List<Range> sources(String family) {
        int at = family.indexOf("|s=");
        if (at < 0) return EVERY_PORT;
        List<Range> out = new ArrayList<>();
        for (String part : family.substring(at + 3).split(",")) { String[] ends = part.split("-"); out.add(new Range(Long.parseLong(ends[0]), Long.parseLong(ends[1]))); }
        return out;
    }
    /**
     * X1: the partial prior provably cannot be the single earlier rule that covers the match. The prior's match lies
     * inside the product of its resolved selectors (unresolved dimensions are the universe; narrowing constraints, an
     * unknown action or enabled state and a placement only shrink it; a Check Point access-role bound is an upper
     * bound), and the match is a full match, a product of non-empty dimensions. A value of the match outside the prior's
     * selector on one dimension is a packet of the match the prior cannot match, in any placement and whatever the
     * unresolved dimensions are. "Outside" counts only where {@link #holds} would answer FALSE, so it is the evidence a
     * full prior's non-containment gives: source and destination always; service only when neither service is
     * application-default; application only when neither service is application-default and neither application
     * selector carries an opaque identity. An unresolved dimension, or one with a token in play, gives no verdict.
     * The work is spent from {@code budget}; running out of it throws {@link Unknown}.
     */
    static boolean cannotCover(Partial prior, Match match, Budget budget) {
        var outer = prior.selectors(); var inner = match.selectors();
        for (int i = 0; i < 2; i++) if (outer.get(i) != null && !contains(outer.get(i), inner.get(i), budget)) return true;
        var service = outer.get(2); var laterService = inner.get(2);
        // Without a resolved prior service the prior might be application-default; with one on either side no
        // default-port or application dimension decides (holds answers null there, never FALSE).
        if (service == null || service.applicationDefault() || laterService.applicationDefault()) return false;
        if (!contains(service, laterService, budget)) return true;
        var application = outer.get(3); var laterApplication = inner.get(3);
        return application != null && application.tokens().isEmpty() && laterApplication.tokens().isEmpty() && !contains(application, laterApplication, budget);
    }
    static boolean disjoint(Match a, Match b, Map<String, PolicyObject> objects) {
        return disjoint(a.placements(), b.placements(), objects);
    }
    static boolean disjoint(Map<String, Set<String>> a, Map<String, Set<String>> b, Map<String, PolicyObject> objects) {
        for (String key : List.of("from", "to", "install-on")) {
            var left = a.get(key); var right = b.get(key);
            if (left.isEmpty() || right.isEmpty()) continue;
            if (key.equals("install-on")) {
                // Resolved group membership, wildcard targets and opaque (unresolved or unsupported) targets are not
                // distinct atomic placements: "Policy Targets" may well include a gateway another rule names.
                if (java.util.stream.Stream.concat(left.stream(), right.stream()).anyMatch(id -> {
                        var o = objects.get(id);
                        return o == null || !o.status().equals("RESOLVED") || o.type().equals("any") || !o.members().isEmpty();
                    })) continue;
            } else if (left.contains("any") || right.contains("any")) continue;
            if (Collections.disjoint(left, right)) return true;
        }
        return false;
    }
    static boolean compatible(Match covering, Match covered) {
        for (String key : List.of("from", "to", "install-on")) {
            var a = covering.placements().get(key); var b = covered.placements().get(key);
            // Exact opaque selector equality is safe; do not infer topology from display names.
            if ((key.equals("from") || key.equals("to")) && a.equals(Set.of("any"))) continue;
            if (!a.equals(b)) return false;
        }
        return true;
    }
    /**
     * Palo Alto zone coverage: every (source zone, destination zone) pair the later rule can match is matched by an
     * earlier universal rule with zones {@code from} x {@code to}. Rule types per the PAN-OS 11.1 admin guide,
     * "Components of a Security Policy Rule", Rule Type
     * (https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/policy/security-policy/components-of-a-security-policy-rule):
     * a universal rule (the default) matches "all matching interzone and intrazone traffic in the specified source and
     * destination zones" -- the vendor's own example: source zones A, B and destination zones A, B match traffic within A,
     * within B, and A to B and B to A, so its zone match is from x to. An intrazone rule matches traffic within each listed
     * source zone only, and no destination zone can be set for it: pairs (z, z), z in from. An interzone rule matches
     * traffic between the listed source and destination zones, not within one: from x to without (z, z). Hence:
     * <ul>
     * <li>universal or interzone later rule: covered when later.from is within {@code from} and later.to within
     * {@code to} (for interzone this is sufficient, not necessary);</li>
     * <li>intrazone later rule: covered when later.from is within both {@code from} and {@code to}; its stored to-zones
     * are not read.</li>
     * </ul>
     * Only universal rules are earlier rules here; an intrazone or interzone earlier rule never covers (not proven).
     * {@link #zoneSuperset} decides "within".
     */
    static boolean zonesCover(Set<String> from, Set<String> to, Set<String> laterFrom, Set<String> laterTo, boolean intrazone) {
        return zoneSuperset(from, laterFrom) && zoneSuperset(to, intrazone ? laterFrom : laterTo);
    }
    /**
     * Every zone of {@code inner} is a zone of {@code outer}, comparing opaque zone names exactly. {@code any} alone is
     * every zone, as {@link #compatible} has always read it (an earlier zone of exactly any). A set that mixes any with
     * zone names is not interpreted: it is within only itself or a lone any, and holds only itself. Equal sets are always
     * within each other.
     */
    static boolean zoneSuperset(Set<String> outer, Set<String> inner) { return Boolean.TRUE.equals(zoneHolds(outer, inner)); }
    /**
     * Whether {@code outer} holds every zone of {@code inner}: TRUE, FALSE, or {@code null} when that cannot be decided.
     * FALSE only between two sets of zone names where {@code inner} names a zone {@code outer} does not: a rule names
     * only zones that exist, so the later rule matches traffic in that zone the earlier rule does not. Undecided: a set
     * mixing any with names (or empty), and a lone any against names -- the zones of the virtual system are not
     * collected, so a list of names may well be every zone.
     */
    static Boolean zoneHolds(Set<String> outer, Set<String> inner) {
        if (outer.equals(ANY_ZONE) || outer.equals(inner)) return true;
        if (outer.isEmpty() || inner.isEmpty() || outer.contains("any") || inner.contains("any")) return null;
        return outer.containsAll(inner);
    }
    /**
     * An earlier universal rule with zones {@code from} x {@code to} provably cannot cover a later universal rule: on
     * from or on to the later rule names a zone the earlier one does not ({@link #zoneHolds} FALSE). With the
     * engine's single-rule cover (NOT_SHADOWED = no single earlier rule fully covers) such a prior is simply no
     * candidate, like a partially overlapping address or service.
     */
    static boolean zonesCannotCover(Set<String> from, Set<String> to, Set<String> laterFrom, Set<String> laterTo) {
        return Boolean.FALSE.equals(zoneHolds(from, laterFrom)) || Boolean.FALSE.equals(zoneHolds(to, laterTo));
    }
    private static final Set<String> ANY_ZONE = Set.of("any");
    /** The Palo Alto rule type of a stored rule; an absent rule-type is the vendor default, universal. */
    static String ruleType(Rule rule) {
        var values = rule.extras().get("rule-type");
        return values == null || values.equals(List.of("universal")) ? "universal" : values.size() == 1 ? values.get(0) : "unknown";
    }
    /**
     * Whether earlier selectors {@code outer} hold every packet of later selectors {@code inner}: TRUE, FALSE, or
     * {@code null} when the answer depends on default ports that are not collected (Palo Alto application-default,
     * {@link #APPLICATION_DEFAULT}) or on how opaque applications overlap ({@link Resolver#contentApplication}).
     * Without application-default on either side, source, destination and service must be contained (else FALSE: a
     * packet of the later rule on the missing address or port lies outside, whatever its application), and then the
     * applications decide: contained ({@link #contains}: any, or a superset of identities) is TRUE; otherwise, where an
     * opaque application identity is on either side, undecided ({@code null}) -- a later application, or the later
     * any, may well carry only traffic of the earlier applications -- and FALSE only between selectors without
     * identities (exactly the former four-dimension {@link #contains}). With application-default, source and
     * destination must still be contained (else FALSE: no default port puts a packet of the later rule back inside),
     * and on service and application:
     * <ul>
     * <li>earlier service any: TRUE when the earlier applications contain the later ones (every port of them);</li>
     * <li>both application-default: TRUE when the earlier application selector contains the later one by identity --
     * the same references or a superset of them, or any against any. Each application then brings its own standard ports
     * to both rules. Earlier application any does not hold later named applications: section 6 leaves the interaction
     * with application any UNKNOWN, so any with application-default is not read as every application's default ports;</li>
     * <li>everything else is undecided ({@code null}), never FALSE: explicit ports may or may not hold an application's
     * default ports, application-default may or may not hold explicit ports, and two application names may denote
     * overlapping traffic.</li>
     * </ul>
     */
    static Boolean holds(List<Selector> outer, List<Selector> inner, Budget budget) {
        Selector service = outer.get(2), laterService = inner.get(2), application = outer.get(3), laterApplication = inner.get(3);
        if (!service.applicationDefault() && !laterService.applicationDefault()) {
            for (int i = 0; i < 3; i++) if (!contains(outer.get(i), inner.get(i), budget)) return false;
            if (contains(application, laterApplication, budget)) return true;
            return application.tokens().isEmpty() && laterApplication.tokens().isEmpty() ? false : null;
        }
        for (int i = 0; i < 2; i++) if (!contains(outer.get(i), inner.get(i), budget)) return false;
        if (service.any()) return contains(application, laterApplication, budget) ? true : null;
        if (service.applicationDefault() && laterService.applicationDefault()
                && (application.any() ? laterApplication.any() : !laterApplication.any() && contains(application, laterApplication, budget))) return true;
        return null;
    }
    static boolean contains(Selector a, Selector b, Budget budget) {
        if (a.any()) return true;
        if (b.any()) return false;
        // Opaque tokens hold only themselves: application-default holds no explicit port and no explicit port holds it;
        // an application reference set holds its subsets.
        if (!a.tokens().containsAll(b.tokens())) return false;
        for (var entry : b.ranges().entrySet()) {
            String family = entry.getKey();
            if (family.contains("|s=") || a.ranges().keySet().stream().anyMatch(k -> k.startsWith(family + "|s="))) {
                if (!containsWithSources(a, family, entry.getValue(), budget)) return false;
                continue;
            }
            if (!within(a.ranges().getOrDefault(family, List.of()), entry.getValue(), budget)) return false;
        }
        return true;
    }
    /** Every inner range lies inside one merged outer range (both sorted and merged). */
    private static boolean within(List<Range> outer, List<Range> innerRanges, Budget budget) {
        int i = 0;
        for (Range inner : innerRanges) {
            budget.spend();
            while (i < outer.size() && outer.get(i).end() < inner.start()) { budget.spend(); i++; }
            if (i == outer.size() || outer.get(i).start() > inner.start() || outer.get(i).end() < inner.end()) return false;
        }
        return true;
    }
    /**
     * Exact containment of a TCP/UDP family when source ports are in play on either side: every (source port,
     * destination port) pair of the inner family lies in some outer family of the same protocol. The inner source ports
     * are cut at every outer source-range boundary; on each piece the destination ports of the outer families whose
     * sources hold the piece are merged and must hold the inner destinations.
     */
    private static boolean containsWithSources(Selector a, String family, List<Range> destinations, Budget budget) {
        String protocol = protocol(family);
        List<Map.Entry<String, List<Range>>> outer = new ArrayList<>();
        for (var e : a.ranges().entrySet()) if (protocol(e.getKey()).equals(protocol)) outer.add(e);
        TreeSet<Long> cuts = new TreeSet<>();
        for (Range r : sources(family)) { cuts.add(r.start()); cuts.add(r.end() + 1); }
        for (var e : outer) for (Range r : sources(e.getKey())) { budget.spend(); cuts.add(r.start()); cuts.add(r.end() + 1); }
        for (Range r : sources(family)) {
            for (Long from : cuts.subSet(r.start(), true, r.end(), true)) {
                budget.spend();
                Long next = cuts.higher(from); long to = Math.min(r.end(), next == null ? r.end() : next - 1);
                List<Range> merged = new ArrayList<>();
                for (var e : outer) if (sources(e.getKey()).stream().anyMatch(s -> s.start() <= from && s.end() >= to)) merged.addAll(e.getValue());
                merged.sort(Comparator.comparingLong(Range::start));
                List<Range> union = new ArrayList<>();
                for (Range m : merged) {
                    budget.spend();
                    if (!union.isEmpty() && union.get(union.size() - 1).end() + 1 >= m.start()) {
                        var last = union.remove(union.size() - 1); union.add(new Range(last.start(), Math.max(last.end(), m.end())));
                    } else union.add(m);
                }
                if (!within(union, destinations, budget)) return false;
            }
        }
        return true;
    }
    private static final Selector APPLICATION_DEFAULT_SERVICE = new Selector(false, Map.of(), Set.of(APPLICATION_DEFAULT));
    static final class Resolver {
        final Map<String, PolicyObject> objects; final Budget budget;
        final Map<String, Selector> memo = new HashMap<>();
        /** The snapshot id whose mapper references identify application-default and applications; null: not recognised. */
        private final String panSnapshot;
        /**
         * Shadowing only: ICMP services, the Check Point comparison port forms, Palo Alto source ports and negated address
         * cells resolve (see {@link #service}, {@link #cell}). Lookup, NAT and path analysis keep the plain resolver,
         * whose queries read the "ipv4", "tcp" and "udp" families only, so for them these stay unresolved as before.
         */
        final boolean extended;
        /**
         * Reference switch for tests only: false gives the former resolution (no extended construct), against which the
         * differential tests compare the earlier engine. Production never changes it.
         */
        static final ThreadLocal<Boolean> EXTENDED = ThreadLocal.withInitial(() -> true);
        Resolver(Map<String, PolicyObject> objects, Budget budget) { this(objects, budget, null, false); }
        private Resolver(Map<String, PolicyObject> objects, Budget budget, String panSnapshot, boolean extended) {
            this.objects = objects; this.budget = budget; this.panSnapshot = panSnapshot; this.extended = extended;
        }
        /**
         * The resolver of shadowing ({@code PolicyHygiene}): for a Palo Alto snapshot it also resolves application-default
         * to its token ({@link #APPLICATION_DEFAULT}) and that rule's applications to opaque references. Lookup, offline
         * analysis and path analysis keep the plain resolver, for which application-default stays "missing service values".
         */
        static Resolver shadowing(PolicySnapshot snapshot, Budget budget) {
            var metadata = snapshot.metadata();
            boolean pan = metadata.vendor().equals("PAN") && metadata.id() != null && !metadata.id().isBlank();
            return new Resolver(snapshot.objects(), budget, pan ? metadata.id() : null, EXTENDED.get());
        }
        Selector cell(Cell cell, String family) {
            if (cell.negated()) {
                if (!extended || !family.equals("address")) throw new Unknown("negated selector");
                return complement(cell(new Cell(cell.refs(), false), family));
            }
            if (cell.refs().isEmpty()) throw new Unknown("missing selector");
            if (family.equals("service") && applicationDefault(cell)) { budget.spend(); return APPLICATION_DEFAULT_SERVICE; }
            List<Selector> parts = new ArrayList<>();
            for (String id : cell.refs()) {
                budget.spend();
                // A content-provided application named directly in the cell is one opaque identity (contentApplication).
                var o = family.equals("application") ? objects.get(id) : null;
                parts.add(o != null && contentApplication(id, o) ? new Selector(false, Map.of(), Set.of(id)) : object(id, family, new HashSet<>()));
            }
            return union(parts);
        }
        Selector object(String id, String family, Set<String> path) {
            budget.spend(); String key = family + ":" + id;
            if (memo.containsKey(key)) return memo.get(key);
            var o = objects.get(id);
            if (o == null || !o.status().equals("RESOLVED")) throw new Unknown("unresolved object");
            if (!path.add(id) || path.size() > 32) throw new Unknown("group cycle or depth limit");
            Selector result;
            if (o.type().equals("any")) result = new Selector(true, Map.of());
            else if (Set.of("group", "address-group", "service-group", "application-group").contains(o.type())) {
                if (!(family.equals("address") && Set.of("group", "address-group").contains(o.type())
                        || family.equals("service") && o.type().equals("service-group")
                        || family.equals("application") && o.type().equals("application-group"))) throw new Unknown("dynamic or unsupported object type");
                if (o.members().isEmpty() || !o.values().isEmpty()) throw new Unknown("empty or dynamic group");
                List<Selector> children = new ArrayList<>();
                for (String member : o.members()) children.add(object(member, family, path));
                result = union(children);
            } else if (family.equals("address") && Set.of("address", "host", "network", "address-range").contains(o.type())) result = address(o);
            else if (family.equals("service") && Set.of("service", "service-tcp", "service-udp").contains(o.type())) result = service(o);
            else throw new Unknown("dynamic or unsupported object type");
            path.remove(id); memo.put(key, result); return result;
        }
        /**
         * The service cell is exactly the mapper's built-in application-default: the one reference PanoramaPolicyMapper
         * mints for it (opaque {@code ref(snapshot, "service", "application-default")}, verified, never matched by display
         * name) to a RESOLVED service object with no values and no members. A cell mixing it with other services is
         * resolved as before (and fails on it as "missing service values").
         */
        private boolean applicationDefault(Cell cell) {
            if (panSnapshot == null || cell.refs().size() != 1 || !cell.refs().get(0).equals(ref(panSnapshot, "service", "application-default"))) return false;
            var o = objects.get(cell.refs().get(0));
            return o != null && o.status().equals("RESOLVED") && o.type().equals("service") && o.values().isEmpty() && o.members().isEmpty();
        }
        /**
         * The application cell of an application-default rule as opaque application references (tokens). Application any
         * is any; an application group is the union of its members; a stored custom application (type application,
         * RESOLVED) and an application the mapper has no definition for -- a predefined App-ID application, stored type
         * unresolved, status UNRESOLVED, under its own application reference {@code ref(snapshot, "application", name)} --
         * are each one reference. These are compared by identity only (equal references, subsets), never interpreted:
         * the same reference names the same application in both rules, so it brings the same standard ports to both. An
         * application filter (dynamic), a missing object or any other shape stays unresolvable, as before.
         */
        Selector applications(Cell cell) {
            if (cell.negated()) throw new Unknown("negated selector");
            if (cell.refs().isEmpty()) throw new Unknown("missing selector");
            List<Selector> parts = new ArrayList<>();
            for (String id : cell.refs()) { budget.spend(); parts.add(application(id, new HashSet<>())); }
            return union(parts);
        }
        /**
         * An upper bound of the source addresses a Check Point source cell naming access roles can match: the union of
         * each access role's networks and of the cell's other address objects, each resolved exactly as {@link #object}
         * resolves an address. Only {@link PolicyNormalization#partial} reads it, and only to prove that an earlier
         * access-role rule cannot cover a later rule (a disjoint source); it is never a full match, a cover or a lookup
         * selector.
         * <p>Vendor semantics. The access role's networks (CheckPointPolicyMapper#accessRoleNetworks; Management API
         * show-access-role "networks": "Collection of Network objects identified by the name or UID that can access the
         * system", https://sc1.checkpoint.com/documents/latest/APIs/index.html#web/show-access-role) restrict the IP
         * addresses whose identity the role matches. R82 Identity Awareness Administration Guide
         * (https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_IdentityAwareness_AdminGuide/CP_R82_IdentityAwareness_AdminGuide.pdf):
         * an access role configures "specified users, computers, and network locations as one object"; pdp roles fetch
         * -ip calculates "Access Roles that also contain explicitly selected objects in the Networks pane" for the IP
         * address of an identity; and where an unidentified source is redirected to the Captive Portal (all other rule
         * fields match), the guide's remedy against matching an address outside the intended ones is to "add a network
         * restriction to the Access Role". So whatever identities the role selects -- every subset of the networks'
         * addresses is possible -- its match lies within its networks; that is the whole claim made here.
         * <p>Residual (stated, not assumed away): with X-Forwarded-For detection on the layer (not collected) a proxied
         * HTTP connection takes its identity from the header, so the role may also match some packets from a proxy
         * outside its networks. It still cannot match a packet of the later rule that carries no such header, so it still
         * cannot cover the later rule, which is all a NOT_SHADOWED verdict claims (no single earlier rule covers it).
         * <p>Fail-closed: networks any (or any member resolving to any) is no bound; an access role without networks
         * values, with members, with a value of another kind, or with a network that does not resolve as an address (not
         * in the dictionary, IPv6, a group with exclusion, ...) is no bound; a negated or empty cell is no bound; the role
         * must be the mapper's stored shape (type access-role, status DYNAMIC). Destination access roles are not bounded.
         */
        Selector accessRoleBound(Cell cell) {
            if (cell.negated()) throw new Unknown("negated selector");
            if (cell.refs().isEmpty()) throw new Unknown("missing selector");
            List<Selector> parts = new ArrayList<>(); boolean role = false;
            for (String id : cell.refs()) {
                budget.spend(); var o = objects.get(id);
                if (o != null && o.type().equals("access-role") && o.status().equals("DYNAMIC")) { role = true; parts.add(accessRole(id, o)); }
                else parts.add(object(id, "address", new HashSet<>()));
            }
            if (!role) throw new Unknown("dynamic or unsupported object type");
            return union(parts);
        }
        private Selector accessRole(String id, PolicyObject o) {
            budget.spend(); String key = "access-role-networks:" + id;
            if (memo.containsKey(key)) return memo.get(key);
            if (!o.members().isEmpty() || o.values().isEmpty()) throw new Unknown("dynamic or unsupported object type");
            List<Selector> parts = new ArrayList<>();
            for (String value : o.values()) {
                budget.spend();
                if (!value.startsWith("networks: ")) throw new Unknown("dynamic or unsupported object type");
                String network = value.substring("networks: ".length());
                parts.add(network.equals("any") ? new Selector(true, Map.of()) : object(network, "address", new HashSet<>()));
            }
            var result = union(parts); memo.put(key, result); return result;
        }
        private Selector application(String id, Set<String> path) {
            budget.spend(); String key = "application-reference:" + id;
            if (memo.containsKey(key)) return memo.get(key);
            var o = objects.get(id);
            if (o == null) throw new Unknown("unresolved object");
            boolean predefined = contentApplication(id, o);
            if (!predefined && !o.status().equals("RESOLVED")) throw new Unknown("unresolved object");
            if (!path.add(id) || path.size() > 32) throw new Unknown("group cycle or depth limit");
            Selector result;
            if (predefined) result = new Selector(false, Map.of(), Set.of(id));
            else if (o.type().equals("any")) result = new Selector(true, Map.of());
            else if (o.type().equals("application-group")) {
                if (o.members().isEmpty() || !o.values().isEmpty()) throw new Unknown("empty or dynamic group");
                List<Selector> children = new ArrayList<>();
                for (String member : o.members()) children.add(application(member, path));
                result = union(children);
            } else if (o.type().equals("application") && o.members().isEmpty()) result = new Selector(false, Map.of(), Set.of(id));
            else throw new Unknown("dynamic or unsupported object type");
            path.remove(id); memo.put(key, result); return result;
        }
        /**
         * A Palo Alto application a rule names that the collected configuration does not define. PanoramaPolicyMapper
         * stores such a name as an UNRESOLVED object of type unresolved, without values or members, under its own
         * application reference {@code ref(snapshot, "application", name)}: no application, application group or
         * application filter of that name exists in the fetched device group or its ancestors (Shared). The predefined
         * App-ID applications (for example ssl or web-browsing) come from the firewall's content updates and are never
         * part of the fetched configuration.
         * <p>Why such a name is read as an application at all (reasoning, not a quoted vendor statement): PAN-OS checks
         * the names a security rule references when the configuration is committed, so a rule naming an application
         * that neither the configuration nor the installed content knows would make the commit fail. A stored rule
         * naming one that the configuration does not define therefore names an application the content provides, not
         * an arbitrary unknown object.
         * <p>Shadowing reads it as an opaque application identity only, compared by its object reference, never by what
         * its name might mean: the same reference denotes the same (fixed, non-empty) traffic wherever the snapshot
         * names it, and nothing else is assumed. Two different applications are never assumed to be disjoint, nor one
         * to exclude or include the other -- one application can carry or include another's traffic (App-ID
         * dependencies, implicitly allowed applications, an application identified only after its first packets). On
         * the application dimension ({@link PolicyNormalization#holds}) an earlier any therefore holds everything, an
         * earlier set of identities holds a later set only when it contains every later identity, and any other pair
         * is undecided, never "does not cover", so it can only leave the later rule UNKNOWN. Identities are never
         * disjoint from anything ({@link PolicyNormalization#disjoint(Selector, Selector, Budget)}).
         * <p>Rules with explicit or any service read only the references directly in their application cell this way
         * ({@link #cell}); application groups, filters and custom applications keep their former handling there, and an
         * UNRESOLVED address or service object stays unresolvable. Application-default rules read the same identities
         * through {@link #applications}.
         */
        private boolean contentApplication(String id, PolicyObject o) {
            return panSnapshot != null && o.status().equals("UNRESOLVED") && o.type().equals("unresolved") && o.members().isEmpty()
                && o.values().isEmpty() && id.equals(ref(panSnapshot, "application", o.name()));
        }
        Selector union(List<Selector> parts) {
            Map<String, List<Range>> ranges = new TreeMap<>(); boolean any = false;
            Set<String> tokens = new HashSet<>();
            for (Selector part : parts) {
                any |= part.any();
                for (String token : part.tokens()) { budget.spend(); tokens.add(token); }
                for (var entry : part.ranges().entrySet()) for (Range r : entry.getValue()) { budget.spend(); ranges.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(r); }
            }
            Map<String, List<Range>> merged = new TreeMap<>();
            ranges.forEach((key, list) -> {
                list.sort(Comparator.comparingLong(Range::start)); List<Range> out = new ArrayList<>();
                for (Range r : list) {
                    budget.spend();
                    if (!out.isEmpty() && out.get(out.size() - 1).end() + 1 >= r.start()) {
                        var previous = out.remove(out.size() - 1); out.add(new Range(previous.start(), Math.max(previous.end(), r.end())));
                    } else out.add(r);
                }
                merged.put(key, List.copyOf(out));
            });
            // Any holds every token; a selector that is any carries none, so equal matches stay equal.
            return new Selector(any, merged, any ? Set.of() : tokens);
        }
        Selector address(PolicyObject o) {
            List<Range> ranges = new ArrayList<>(); Map<String, String> fields = fields(o);
            try {
                for (var entry : fields.entrySet()) {
                    String key = entry.getKey(), value = entry.getValue();
                    if (Set.of("ipv4-address", "ip-netmask", "ip-range", "literal").contains(key)) {
                        var r = PolicyRuleQuery.interval(value); ranges.add(new Range(r[0], r[1]));
                    } else if (!Set.of("subnet4", "mask-length4", "ip-address-first", "ip-address-last", "ipv4-address-first", "ipv4-address-last").contains(key))
                        throw new Unknown("unsupported address representation");
                }
                if (fields.containsKey("subnet4") || fields.containsKey("mask-length4")) {
                    var r = PolicyRuleQuery.interval(fields.get("subnet4") + "/" + fields.get("mask-length4")); ranges.add(new Range(r[0], r[1]));
                }
                // Check Point object representation (ipv4-address-first/-last); the generic ip-address-* spelling is
                // accepted too. Both spellings on one object is not a proven single range.
                boolean range = Set.of("ip-address-first", "ip-address-last", "ipv4-address-first", "ipv4-address-last").stream().anyMatch(fields::containsKey);
                if (range && fields.containsKey("ipv4-address")) throw new Unknown("ambiguous object values");
                for (String prefix : List.of("ip-address-", "ipv4-address-")) {
                    if (!fields.containsKey(prefix + "first") && !fields.containsKey(prefix + "last")) continue;
                    if (prefix.equals("ipv4-address-") && (fields.containsKey("ip-address-first") || fields.containsKey("ip-address-last")))
                        throw new Unknown("ambiguous object values");
                    var r = PolicyRuleQuery.interval(fields.get(prefix + "first") + "-" + fields.get(prefix + "last")); ranges.add(new Range(r[0], r[1]));
                }
            } catch (IllegalArgumentException invalid) { throw new Unknown("unsupported address representation"); }
            if (ranges.isEmpty()) throw new Unknown("missing address values");
            return union(List.of(new Selector(false, Map.of("ipv4", ranges))));
        }
        Map<String, String> fields(PolicyObject o) {
            if (!o.members().isEmpty()) throw new Unknown("unsupported object members");
            Map<String, String> fields = new LinkedHashMap<>();
            for (String value : o.values()) {
                budget.spend(); int split = value.indexOf(": ");
                if (fields.put(split < 0 ? "literal" : value.substring(0, split), split < 0 ? value : value.substring(split + 2)) != null)
                    throw new Unknown("ambiguous object values");
            }
            return fields;
        }
        Selector service(PolicyObject o) {
            var fields = fields(o); Map<String, List<Range>> ranges = new TreeMap<>();
            String protocol = fields.get("protocol");
            if (extended && ("icmp".equals(protocol) || "icmp6".equals(protocol))) return icmp(protocol, fields);
            for (var entry : fields.entrySet()) {
                String key = entry.getKey(); if (key.equals("protocol")) continue;
                if (extended && SOURCE_PORT_FIELDS.contains(key)) continue;
                String proto = key.equals("port") ? protocol : key.equals("protocol/tcp/port") ? "tcp" : key.equals("protocol/udp/port") ? "udp" : null;
                if (proto == null || !Set.of("tcp", "udp").contains(proto)) throw new Unknown("unsupported service constraint");
                for (String port : entry.getValue().split(",", -1)) { budget.spend(); ranges.computeIfAbsent(proto, p -> new ArrayList<>()).add(port(port)); }
            }
            // Palo Alto source port (extended only): the service's destination ports under a source-port family.
            for (String proto : List.of("tcp", "udp")) {
                String sources = extended ? fields.get("protocol/" + proto + "/source-port") : null;
                if (sources == null) continue;
                var destinations = ranges.remove(proto);
                if (destinations == null) throw new Unknown("unsupported service constraint");
                List<Range> list = new ArrayList<>();
                for (String port : sources.split(",", -1)) { budget.spend(); list.add(port(port)); }
                var merged = union(List.of(new Selector(false, Map.of(proto, list)))).ranges().get(proto);
                boolean every = merged.size() == 1 && merged.get(0).start() <= 0 && merged.get(0).end() >= 65535;
                ranges.put(every ? proto : sourceFamily(proto, merged), destinations);
            }
            if (ranges.isEmpty()) throw new Unknown("missing service values");
            return union(List.of(new Selector(false, ranges)));
        }
        /**
         * One port expression: N or N-M (Management API add-service-tcp "port": "To specify a port range, place a hyphen
         * between the lowest and highest port numbers"). The Check Point comparison forms {@code >N} / {@code <N} are not
         * described by any official page read (2026-11-13), so they stay unresolved.
         */
        private Range port(String raw) {
            String port = raw.trim();
            try {
                if (!port.matches("[0-9]+(?:-[0-9]+)?")) throw new Unknown("unsupported port expression");
                String[] ends = port.split("-"); int a = Integer.parseInt(ends[0]), b = Integer.parseInt(ends[ends.length - 1]);
                if (a < 0 || b > 65535 || a > b) throw new NumberFormatException();
                return new Range(a, b);
            } catch (NumberFormatException invalid) { throw new Unknown("invalid port range"); }
        }
        /**
         * A Check Point ICMP or ICMPv6 service (extended only): family {@code icmp} / {@code icmp6}, value type x 256 + code
         * ("ICMP Type and Code, as listed in RFC 792", SmartConsole R80.30 help "ICMP"). Only with a stored code: the
         * Management API gives {@code icmp-code} the default 0 rather than "every code", and a stored object without the
         * field does not say which code it has, so it stays unresolved. Any other field leaves it unresolved too.
         */
        private Selector icmp(String protocol, Map<String, String> fields) {
            for (String key : fields.keySet()) if (!Set.of("protocol", "icmp-type", "icmp-code").contains(key)) throw new Unknown("unsupported service constraint");
            budget.spend();
            try {
                int type = Integer.parseInt(Objects.requireNonNull(fields.get("icmp-type"), "type").trim());
                if (type < 0 || type > 255) throw new NumberFormatException();
                String code = fields.get("icmp-code");
                if (code == null) throw new Unknown("unsupported service constraint");
                int c = Integer.parseInt(code.trim());
                if (c < 0 || c > 255) throw new NumberFormatException();
                return new Selector(false, Map.of(protocol, List.of(new Range(type * 256L + c, type * 256L + c))));
            } catch (NumberFormatException | NullPointerException invalid) { throw new Unknown("unsupported service constraint"); }
        }
        /**
         * The complement of a resolved address selector (extended only, a negated cell): every IPv4 address outside it and
         * every IPv6 address (family {@code ipv6}, one range; the engine resolves no IPv6 object, so a negated IPv4 cell
         * also matches all IPv6 traffic). A negated any matches nothing and stays unresolved.
         */
        private Selector complement(Selector positive) {
            if (positive.any() || !positive.tokens().isEmpty() || !positive.ranges().keySet().stream().allMatch("ipv4"::equals)) throw new Unknown("negated selector");
            List<Range> out = new ArrayList<>(); long next = 0;
            for (Range r : positive.ranges().getOrDefault("ipv4", List.of())) {
                budget.spend();
                if (r.start() > next) out.add(new Range(next, r.start() - 1));
                next = Math.max(next, r.end() + 1);
            }
            if (next <= IPV4_MAX) out.add(new Range(next, IPV4_MAX));
            Map<String, List<Range>> ranges = new TreeMap<>();
            if (!out.isEmpty()) ranges.put("ipv4", List.copyOf(out));
            ranges.put("ipv6", List.of(new Range(0, Long.MAX_VALUE)));
            return new Selector(false, ranges);
        }
    }}
