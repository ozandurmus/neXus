package com.securityexpert.nexus.ui2.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import org.w3c.dom.Element;
import java.util.*;

/** Offline management-intent mapper, with a separate stored local-firewall projection. */
public final class PanoramaPolicyMapper {
    private record Definition(Element element, String type, String scope) {}

    /** Parent relationships and precedence are explicit collection inputs, not inferred from firewall configuration. */
    public PolicySnapshot map(Metadata metadata, String xml, String deviceGroup,
            Map<String, String> parents, boolean ancestorObjectsTakePrecedence) {
        return map(metadata, PolicyXml.parse(xml).getDocumentElement(), deviceGroup, parents, ancestorObjectsTakePrecedence);
    }

    public PolicySnapshot map(Metadata metadata, Element root, String deviceGroup,
            Map<String, String> parents, boolean ancestorObjectsTakePrecedence) {
        if (root.getTagName().equals("response")) {
            var configs = PolicyXml.selectRelative(root, "result/config");
            if (configs.size() != 1) throw new IllegalArgumentException("Missing Panorama configuration");
            root = configs.get(0);
        }
        if (!root.getTagName().equals("config")) throw new IllegalArgumentException("Expected Panorama configuration");
        Map<String, Element> groups = new LinkedHashMap<>();
        for (Element group : PolicyXml.selectRelative(root, "devices/entry/device-group/entry"))
            groups.put(group.getAttribute("name"), group);
        List<String> lineage = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = deviceGroup;
        while (current != null && !current.isEmpty()) {
            if (!visited.add(current) || lineage.size() >= 32 || !groups.containsKey(current))
                throw new IllegalArgumentException("Incomplete or cyclic device-group hierarchy");
            lineage.add(current);
            current = parents.get(current);
        }
        Collections.reverse(lineage);
        List<Element> scopes = new ArrayList<>(PolicyXml.selectRelative(root, "shared"));
        for (String name : lineage) scopes.add(groups.get(name));
        if (scopes.isEmpty()) throw new IllegalArgumentException("No management policy scope");
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (Element scope : scopes) {
            for (String type : List.of("address", "address-group", "service", "service-group", "application-group", "tag")) {
                for (Element entry : PolicyXml.selectRelative(scope, type + "/entry")) {
                    String key = family(type) + ":" + entry.getAttribute("name");
                    var definition = new Definition(entry, type, scopeName(scope));
                    if (ancestorObjectsTakePrecedence) definitions.putIfAbsent(key, definition);
                    else definitions.put(key, definition);
                }
            }
        }
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        for (var entry : definitions.entrySet()) {
            String family = family(entry.getValue().type());
            object(metadata, family, entry.getValue().element().getAttribute("name"), definitions, objects, 0);
        }
        List<Section> sections = new ArrayList<>();
        for (Element scope : scopes) section(metadata, scope, "pre-rulebase", definitions, objects, sections);
        Collections.reverse(scopes);
        for (Element scope : scopes) section(metadata, scope, "post-rulebase", definitions, objects, sections);
        return new PolicySnapshot(metadata, sections, objects);
    }
    /** Approved reads do not expose the global object-precedence setting: retain uncertainty on collisions. */
    public PolicySnapshot mapUnverifiedPrecedence(Metadata metadata, Element config, String group, Map<String, String> parents) {
        var descendant = map(metadata, config, group, parents, false);
        var ancestor = map(metadata, config, group, parents, true);
        Map<String, PolicyObject> objects = new LinkedHashMap<>(descendant.objects());
        objects.replaceAll((id, object) -> object.equals(ancestor.objects().get(id)) ? object :
                new PolicyObject(id, object.name(), object.type(), List.of(), List.of(), "UNKNOWN"));
        return new PolicySnapshot(metadata, descendant.sections(), objects);
    }

    /** Stored effective-running only; never fetches a firewall or treats inherited rules as local. */
    public List<PolicySnapshot> local(String deviceId, String collectedAt, String artefactRef, String xml) {
        return local(deviceId, collectedAt, artefactRef,
                PolicyXml.parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), true).getDocumentElement());
    }
    public List<PolicySnapshot> local(String deviceId, String collectedAt, String artefactRef, Element root) {
        if (root.getTagName().equals("response")) {
            if (!"success".equals(root.getAttribute("status"))) throw new IllegalArgumentException("Invalid stored configuration");
            var configs = PolicyXml.selectRelative(root, "result/config");
            if (configs.size() != 1) throw new IllegalArgumentException("Missing stored configuration");
            root = configs.get(0);
        }
        if (!root.getTagName().equals("config")) throw new IllegalArgumentException("Missing stored configuration");
        List<PolicySnapshot> snapshots = new ArrayList<>();
        for (Element vsys : PolicyXml.selectRelative(root, "devices/entry/vsys/entry")) {
            String context = vsys.getAttribute("name");
            if (context.isEmpty()) throw new IllegalArgumentException("Missing virtual system");
            String id = ref(deviceId, "local-firewall-policy", context);
            var metadata = new Metadata(id, deviceId, "Local firewall " + deviceId, "PAN", ref(deviceId, context), context,
                    "Local firewall policy", collectedAt, artefactRef, List.of(new Target(deviceId, "Local firewall " + deviceId, context, "UNKNOWN")));
            Map<String, Definition> definitions = new LinkedHashMap<>();
            var scopes = new ArrayList<>(PolicyXml.selectRelative(root, "shared"));
            scopes.add(vsys);
            for (Element scope : scopes) for (String type : List.of("address", "address-group", "service", "service-group", "application-group", "tag"))
                for (Element entry : PolicyXml.selectRelative(scope, type + "/entry"))
                    definitions.put(family(type) + ":" + entry.getAttribute("name"), new Definition(entry, type, scopeName(scope)));
            Element localScope = (Element) vsys.cloneNode(true);
            // An unmanaged effective-running document can still carry provenance; exclude explicit manager sources.
            for (Element rules : PolicyXml.selectRelative(localScope, "rulebase/security/rules")) {
                for (Element rule : new ArrayList<>(PolicyXml.selectRelative(rules, "entry"))) {
                    String provenance = rule.getAttribute("src");
                    if (!provenance.isEmpty() && !provenance.equals("local")) rules.removeChild(rule);
                }
            }
            Map<String, PolicyObject> objects = new LinkedHashMap<>();
            List<Section> sections = new ArrayList<>();
            section(metadata, localScope, "rulebase", definitions, objects, sections);
            snapshots.add(new PolicySnapshot(metadata, sections, objects));
        }
        return snapshots;
    }

    private void section(Metadata meta, Element scope, String phase, Map<String, Definition> definitions,
            Map<String, PolicyObject> objects, List<Section> sections) {
        String source = scopeName(scope);
        String id = ref(meta.id(), source, phase);
        List<Rule> rules = new ArrayList<>();
        for (Element entry : PolicyXml.selectRelative(scope, phase + "/security/rules/entry")) {
            Map<String, List<String>> extras = new LinkedHashMap<>();
            for (String key : List.of("from", "to", "source-user", "category", "tag", "schedule", "rule-type", "log-setting", "target/negate", "profile-setting/group")) {
                List<String> values = members(entry, key);
                if (!values.isEmpty()) extras.put(key, values);
            }
            // Device serials in target selectors are evidence values, never used as enrolled-device identifiers.
            List<String> targetSelectors = PolicyXml.selectRelative(entry, "target/devices/entry").stream()
                    .map(e -> e.getAttribute("name")).toList();
            if (!targetSelectors.isEmpty()) extras.put("target-selectors", targetSelectors);
            String disabled = text(entry, "disabled");
            String uuid = entry.getAttribute("uuid");
            rules.add(new Rule(ref(id, uuid.isEmpty() ? entry.getAttribute("name") : uuid), uuid, rules.size() + 1,
                    entry.getAttribute("name"), switch (disabled) { case "yes" -> false; case "no" -> true; default -> null; },
                    cell(meta, entry, "source", "address", definitions, objects),
                    cell(meta, entry, "destination", "address", definitions, objects),
                    cell(meta, entry, "service", "service", definitions, objects),
                    cell(meta, entry, "application", "application", definitions, objects),
                    text(entry, "action").isEmpty() ? "UNKNOWN" : text(entry, "action"),
                    "start=" + unknown(text(entry, "log-start")) + ", end=" + unknown(text(entry, "log-end")),
                    text(entry, "description"), extras));
        }
        sections.add(new Section(id, phase.equals("pre-rulebase") ? "Pre rules" : phase.equals("post-rulebase") ? "Post rules" : "Local rules", source, null, rules));
    }
    private Cell cell(Metadata meta, Element entry, String field, String family,
            Map<String, Definition> definitions, Map<String, PolicyObject> objects) {
        return new Cell(members(entry, field).stream().map(name -> object(meta, family, name, definitions, objects, 0)).toList(),
                text(entry, "negate-" + field).equals("yes"));
    }
    private String object(Metadata meta, String family, String name, Map<String, Definition> definitions,
            Map<String, PolicyObject> objects, int depth) {
        String id = ref(meta.id(), family, name);
        if (objects.containsKey(id)) return id;
        if (depth > 32) throw new IllegalArgumentException("Policy object nesting limit exceeded");
        Definition definition = definitions.get(family + ":" + name);
        if (name.equals("any")) {
            objects.put(id, new PolicyObject(id, "ANY", "any", List.of(), List.of(), "RESOLVED"));
        } else if (definition == null) {
            boolean literal = family.equals("address") && (name.contains(".") || name.contains(":")) && name.matches("[0-9a-fA-F.:/\\-]+");
            boolean defaultService = family.equals("service") && name.equals("application-default");
            objects.put(id, new PolicyObject(id, name, defaultService ? "service" : literal ? "address" : "unresolved",
                    List.of(), literal ? List.of(name) : List.of(), literal || defaultService ? "RESOLVED" : "UNRESOLVED"));
        } else {
            var entry = definition.element();
            String type = definition.type();
            objects.put(id, new PolicyObject(id, name, type, List.of(), List.of(), "UNRESOLVED")); // cycle guard
            List<String> memberNames = type.equals("address-group") ? members(entry, "static") : members(entry, "members");
            List<String> refs = memberNames.stream().map(member -> object(meta, family, member, definitions, objects, depth + 1)).toList();
            List<String> values = new ArrayList<>();
            for (String field : List.of("ip-netmask", "ip-range", "fqdn", "dynamic/filter", "protocol/tcp/port", "protocol/tcp/source-port",
                    "protocol/udp/port", "protocol/udp/source-port", "color")) {
                String value = text(entry, field);
                if (!value.isEmpty()) values.add(field + ": " + value);
            }
            String status = !text(entry, "dynamic/filter").isEmpty() ? "DYNAMIC" : "RESOLVED";
            objects.put(id, new PolicyObject(id, name, type, refs, values, status));
        }
        return id;
    }
    private static String family(String type) {
        return switch (type) { case "address-group" -> "address"; case "service-group" -> "service"; case "application-group" -> "application"; default -> type; };
    }
    private static String scopeName(Element scope) { return scope.getTagName().equals("shared") ? "Shared" : scope.getAttribute("name"); }
    private static String text(Element entry, String path) { return PolicyXml.firstRelativeText(entry, path).orElse(""); }
    private static String unknown(String value) { return value.isEmpty() ? "UNKNOWN" : value; }
    private static List<String> members(Element entry, String path) {
        List<Element> members = PolicyXml.selectRelative(entry, path + "/member");
        if (!members.isEmpty()) return members.stream().map(Element::getTextContent).toList();
        String scalar = text(entry, path);
        return scalar.isEmpty() ? List.of() : List.of(scalar);
    }
}
