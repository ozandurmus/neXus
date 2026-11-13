package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.util.*;

/**
 * The legacy (IP-level) view of a stored snapshot for the hygiene legacy mode
 * (POLICY_HYGIENE_MODES_AND_RESOLUTION_DESIGN_2026_11_13.md section 3.2, DRAFT): identity and dynamic-object cells are
 * ignored for the match. A Check Point access role counts as the networks it names (any when it names none or any);
 * every other DYNAMIC object (Check Point dynamic object, updatable object, DNS domain, security zone; Palo Alto external
 * list, application filter, dynamic user group), a Palo Alto dynamic address group and a Palo Alto FQDN address count
 * as any; Palo Alto source-user is ignored. Nothing else changes. Analysed with {@link PolicyHygiene#analyzeLegacy},
 * which also drops time and the other narrowing constraints ({@link PolicyNormalization#upperBound}).
 * <p>Every verdict that rests on an ignored condition is IP-level, never proven shadowing: {@link #flagged} names the
 * rules that resolve only in this view, and a verdict whose rule or covering rule is flagged is shown as IP-level.
 */
final class PolicyLegacyView {
    private PolicyLegacyView() {}

    static PolicySnapshot of(PolicySnapshot snapshot) {
        Map<String, PolicyObject> objects = new HashMap<>(snapshot.objects());
        for (var entry : snapshot.objects().entrySet()) {
            var o = entry.getValue(); String id = entry.getKey();
            boolean role = "access-role".equals(o.type()) && "DYNAMIC".equals(o.status());
            boolean dynamicGroup = "address-group".equals(o.type()) && o.values().stream().anyMatch(v -> v.startsWith("dynamic/filter"));
            boolean fqdn = "address".equals(o.type()) && o.values().stream().anyMatch(v -> v.startsWith("fqdn: "));
            if (role) {
                List<String> networks = new ArrayList<>(); boolean any = o.values().isEmpty();
                for (String v : o.values()) {
                    if (!v.startsWith("networks: ")) { any = true; break; }
                    String network = v.substring("networks: ".length());
                    if (network.equals("any")) any = true; else networks.add(network);
                }
                objects.put(id, any || networks.isEmpty() ? new PolicyObject(id, o.name(), "any", List.of(), List.of(), "RESOLVED")
                    : new PolicyObject(id, o.name(), "group", networks, List.of(), "RESOLVED"));
            } else if ("DYNAMIC".equals(o.status()) || dynamicGroup || fqdn) {
                objects.put(id, new PolicyObject(id, o.name(), "any", List.of(), List.of(), "RESOLVED"));
            }
        }
        List<Section> sections = new ArrayList<>();
        for (var section : snapshot.sections()) {
            List<Rule> rules = new ArrayList<>();
            for (var r : section.rules()) {
                if (r.extras().containsKey("source-user") && !r.extras().get("source-user").equals(List.of("any"))) {
                    Map<String, List<String>> extras = new TreeMap<>(r.extras()); extras.put("source-user", List.of("any"));
                    r = new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(), r.service(), r.application(),
                        r.action(), r.log(), r.comment(), extras, r.hitCounts());
                }
                rules.add(r);
            }
            sections.add(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules));
        }
        return new PolicySnapshot(snapshot.metadata(), sections, objects, snapshot.failures());
    }

    /**
     * The rules that have no exact match in the stored snapshot but an upper-bound match in the legacy view: their
     * verdicts, and every verdict they cover, are IP-level.
     */
    static Set<String> flagged(PolicySnapshot snapshot, PolicySnapshot view) {
        Set<String> out = new HashSet<>();
        var exactBudget = new Budget(PolicyHygiene.TOTAL_WORK); var exact = Resolver.shadowing(snapshot, exactBudget);
        var viewBudget = new Budget(PolicyHygiene.TOTAL_WORK); var legacy = Resolver.shadowing(view, viewBudget);
        for (int i = 0; i < snapshot.sections().size(); i++) {
            var section = snapshot.sections().get(i); var viewSection = view.sections().get(i);
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) continue;
            for (int j = 0; j < section.rules().size(); j++) {
                exactBudget.rule(PolicyHygiene.RULE_WORK); viewBudget.rule(PolicyHygiene.RULE_WORK);
                try {
                    if (PolicyNormalization.normalize(snapshot, section, section.rules().get(j), exact).problem() != null
                            && PolicyNormalization.upperBound(view, viewSection, viewSection.rules().get(j), legacy).problem() == null)
                        out.add(section.rules().get(j).id());
                } catch (Unknown limit) {
                    // Out of work: not flagged, and its verdict is UNKNOWN in both modes anyway.
                }
            }
        }
        return out;
    }
}
