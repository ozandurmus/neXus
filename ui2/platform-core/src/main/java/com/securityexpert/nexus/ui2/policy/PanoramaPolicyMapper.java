package com.securityexpert.nexus.ui2.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import org.w3c.dom.Element;
import java.util.*;

/** Offline management-intent mapper, with a separate stored local-firewall projection. */
public final class PanoramaPolicyMapper {
    private record Definition(Element element, String type, String scope) {}
    /**
     * Object elements read from a shared or device-group scope. The first seven are the original set; the rest were
     * added by the policy-completeness plan (G9) and are parsed from the same approved shared/device-group reads.
     * External-list credentials (auth/*) are never read; see {@link #objectValues}.
     */
    private static final List<String> OBJECT_TYPES = List.of("address", "address-group", "service", "service-group",
            "application", "application-filter", "application-group", "external-list", "region", "dynamic-user-group",
            "profile-group", "tag", "schedule");
    /** The original object set, still used by the stored local-firewall projection. */
    private static final List<String> LOCAL_OBJECT_TYPES = List.of("address", "address-group", "service", "service-group",
            "application-group", "tag", "schedule");

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
            for (String type : OBJECT_TYPES) {
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
        // NAT and default security rules follow the security sections and carry their own section names, so security
        // evaluation (lookup, shadowing, hit joins) can skip them; see PolicySnapshot.panNonSecurity.
        for (Element scope : scopes.reversed()) natSection(metadata, scope, "pre-rulebase", definitions, objects, sections);
        for (Element scope : scopes) natSection(metadata, scope, "post-rulebase", definitions, objects, sections);
        for (Element scope : scopes.reversed()) defaultSection(metadata, scope, "pre-rulebase", sections);
        for (Element scope : scopes) defaultSection(metadata, scope, "post-rulebase", sections);
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
            for (Element scope : scopes) for (String type : LOCAL_OBJECT_TYPES)
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
            for (String key : List.of("from", "to", "source-user", "category", "tag", "schedule", "rule-type", "log-setting", "target/negate", "profile-setting/group", "profile-setting/profiles/virus", "profile-setting/profiles/spyware", "profile-setting/profiles/vulnerability", "profile-setting/profiles/url-filtering", "profile-setting/profiles/file-blocking", "profile-setting/profiles/wildfire-analysis")) {
                List<String> values = members(entry, key);
                if (!values.isEmpty()) extras.put(key, values);
            }
            if (extras.containsKey("schedule")) extras.put("schedule", extras.get("schedule").stream()
                    .map(name -> object(meta, "schedule", name, definitions, objects, 0)).toList());
            // Device serials in target selectors are evidence values, never used as enrolled-device identifiers.
            List<String> targetSelectors = PolicyXml.selectRelative(entry, "target/devices/entry").stream()
                    .map(e -> e.getAttribute("name")).toList();
            if (!targetSelectors.isEmpty()) extras.put("target-selectors", targetSelectors);
            // PAN-OS config XML omits default values: a rule entry with no <disabled> leaf is enabled ("no" is the
            // schema default). Only an explicit value other than yes/no stays unknown.
            String disabled = PolicyXml.firstRelativeText(entry, "disabled").orElse("no").trim();
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

    /**
     * NAT rulebase of one scope and phase, read from the same approved shared/device-group responses as the security
     * rules (policy-completeness plan G7). The official PAN-OS XML schema was not
     * available to this change; every element below is confirmed only against the vendor SDK's {@code NatRule} model
     * (secondary source) except the ones marked UNVERIFIED. Nothing is inferred: a missing leaf stays
     * empty and a section with no entries is not emitted.
     */
    private void natSection(Metadata meta, Element scope, String phase, Map<String, Definition> definitions,
            Map<String, PolicyObject> objects, List<Section> sections) {
        List<Element> entries = PolicyXml.selectRelative(scope, phase + "/nat/rules/entry");
        if (entries.isEmpty()) return;
        String source = scopeName(scope);
        String id = ref(meta.id(), source, phase, "nat");
        List<Rule> rules = new ArrayList<>();
        for (Element entry : entries) {
            Map<String, List<String>> extras = new LinkedHashMap<>();
            for (String key : List.of("from", "to", "tag", "to-interface", "nat-type", "target/negate", "group-tag", "active-active-device-binding")) {
                List<String> values = members(entry, key);
                if (!values.isEmpty()) extras.put(key, values);
            }
            // Source translation: exactly one child of <source-translation> names the type (dynamic-ip-and-port,
            // dynamic-ip or static-ip). An absent element means "no source translation", which the action reflects.
            String sourceType = firstChildName(entry, "source-translation");
            if (!sourceType.isEmpty()) {
                extras.put("source-translation-type", List.of(sourceType));
                String base = "source-translation/" + sourceType;
                putRefs(extras, "translated-source", meta, definitions, objects,
                        members(entry, base + "/translated-address"));
                // interface-address (dynamic-ip-and-port, and the dynamic-ip fallback): interface plus ip or floating-ip.
                putText(extras, "source-translation-interface", entry, base + "/interface-address/interface");
                putText(extras, "source-translation-interface-ip", entry, base + "/interface-address/ip");
                // UNVERIFIED: floating-ip directly under the dynamic-ip-and-port interface-address (the SDK models it only
                // for the dynamic-ip fallback).
                putText(extras, "source-translation-interface-floating-ip", entry, base + "/interface-address/floating-ip");
                putText(extras, "source-translation-bi-directional", entry, base + "/bi-directional");
                String fallbackType = firstChildName(entry, base + "/fallback");
                if (!fallbackType.isEmpty()) {
                    extras.put("source-translation-fallback-type", List.of(fallbackType));
                    String fallback = base + "/fallback/" + fallbackType;
                    putRefs(extras, "translated-source-fallback", meta, definitions, objects, fallbackType.equals("translated-address") ? members(entry, fallback) : List.of());
                    putText(extras, "source-translation-fallback-interface", entry, fallback + "/interface");
                    putText(extras, "source-translation-fallback-ip", entry, fallback + "/ip");
                    putText(extras, "source-translation-fallback-floating-ip", entry, fallback + "/floating-ip");
                }
            }
            boolean destination = !PolicyXml.selectRelative(entry, "destination-translation").isEmpty();
            boolean dynamicDestination = !PolicyXml.selectRelative(entry, "dynamic-destination-translation").isEmpty();
            if (destination) {
                putRefs(extras, "translated-destination", meta, definitions, objects, members(entry, "destination-translation/translated-address"));
                putText(extras, "translated-destination-port", entry, "destination-translation/translated-port");
                // UNVERIFIED: dns-rewrite/direction follows the PAN-OS schema from memory; the vendor SDK has no model for it.
                putText(extras, "destination-dns-rewrite", entry, "destination-translation/dns-rewrite/direction");
            }
            if (dynamicDestination) {
                putRefs(extras, "translated-destination-dynamic", meta, definitions, objects, members(entry, "dynamic-destination-translation/translated-address"));
                putText(extras, "translated-destination-dynamic-port", entry, "dynamic-destination-translation/translated-port");
                putText(extras, "destination-dynamic-distribution", entry, "dynamic-destination-translation/distribution");
            }
            List<String> targetSelectors = PolicyXml.selectRelative(entry, "target/devices/entry").stream()
                    .map(e -> e.getAttribute("name")).toList();
            if (!targetSelectors.isEmpty()) extras.put("target-selectors", targetSelectors);
            String disabled = PolicyXml.firstRelativeText(entry, "disabled").orElse("no").trim();
            String uuid = entry.getAttribute("uuid");
            String action = !sourceType.isEmpty() && (destination || dynamicDestination) ? "source-and-destination-nat"
                    : !sourceType.isEmpty() ? "source-nat" : destination || dynamicDestination ? "destination-nat" : "no-nat";
            rules.add(new Rule(ref(id, uuid.isEmpty() ? entry.getAttribute("name") : uuid), uuid, rules.size() + 1,
                    entry.getAttribute("name"), switch (disabled) { case "yes" -> false; case "no" -> true; default -> null; },
                    cell(meta, entry, "source", "address", definitions, objects),
                    cell(meta, entry, "destination", "address", definitions, objects),
                    // The original service is a single leaf (default "any"), not a member list.
                    cell(meta, entry, "service", "service", definitions, objects),
                    new Cell(List.of(), false), action, "", text(entry, "description"), extras));
        }
        sections.add(new Section(id, phase.equals("pre-rulebase") ? PolicySnapshot.PAN_PRE_NAT : PolicySnapshot.PAN_POST_NAT,
                source, null, rules));
    }

    /**
     * Overridden default security rules (intrazone-default and interzone-default). PAN-OS stores an entry only when
     * the administrator changed the built-in behaviour, so a missing section does not mean "no default rules": the
     * built-in defaults are not synthesized here. UNVERIFIED: whether Panorama also places them under pre-rulebase;
     * both phases are read and only present entries are emitted. The vendor SDK has no model for these rules.
     */
    private void defaultSection(Metadata meta, Element scope, String phase, List<Section> sections) {
        List<Element> entries = PolicyXml.selectRelative(scope, phase + "/default-security-rules/rules/entry");
        if (entries.isEmpty()) return;
        String source = scopeName(scope);
        String id = ref(meta.id(), source, phase, "default-security-rules");
        List<Rule> rules = new ArrayList<>();
        for (Element entry : entries) {
            Map<String, List<String>> extras = new LinkedHashMap<>();
            for (String key : List.of("tag", "log-setting", "profile-setting/group", "profile-setting/profiles/virus", "profile-setting/profiles/spyware", "profile-setting/profiles/vulnerability", "profile-setting/profiles/url-filtering", "profile-setting/profiles/file-blocking", "profile-setting/profiles/wildfire-analysis")) {
                List<String> values = members(entry, key);
                if (!values.isEmpty()) extras.put(key, values);
            }
            String name = entry.getAttribute("name");
            // The two built-in names have documented, fixed scope semantics; any other name carries no rule type.
            if (name.equals("intrazone-default")) extras.put("rule-type", List.of("intrazone"));
            else if (name.equals("interzone-default")) extras.put("rule-type", List.of("interzone"));
            String uuid = entry.getAttribute("uuid");
            rules.add(new Rule(ref(id, uuid.isEmpty() ? name : uuid), uuid, rules.size() + 1, name, true,
                    new Cell(List.of(), false), new Cell(List.of(), false), new Cell(List.of(), false), new Cell(List.of(), false),
                    text(entry, "action").isEmpty() ? "UNKNOWN" : text(entry, "action"),
                    "start=" + unknown(text(entry, "log-start")) + ", end=" + unknown(text(entry, "log-end")),
                    text(entry, "description"), extras));
        }
        sections.add(new Section(id, phase.equals("pre-rulebase") ? PolicySnapshot.PAN_PRE_DEFAULT : PolicySnapshot.PAN_POST_DEFAULT,
                source, null, rules));
    }
    private static String firstChildName(Element entry, String path) {
        for (Element parent : PolicyXml.selectRelative(entry, path))
            for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling())
                if (child instanceof Element element) return element.getTagName();
        return "";
    }
    private static void putText(Map<String, List<String>> extras, String key, Element entry, String path) {
        String value = text(entry, path);
        if (!value.isEmpty()) extras.put(key, List.of(value));
    }
    private void putRefs(Map<String, List<String>> extras, String key, Metadata meta, Map<String, Definition> definitions,
            Map<String, PolicyObject> objects, List<String> names) {
        if (names.isEmpty()) return;
        extras.put(key, names.stream().map(name -> object(meta, "address", name, definitions, objects, 0)).toList());
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
        // Rules reference EDLs and regions in their address selectors; they live in their own definition families so
        // they can never overwrite an address object of the same name.
        if (definition == null && family.equals("address"))
            for (String alternative : List.of("external-list", "region"))
                if ((definition = definitions.get(alternative + ":" + name)) != null) break;
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
            List<String> values = objectValues(entry, type);
            String status = !text(entry, "dynamic/filter").isEmpty()
                    || Set.of("application-filter", "external-list", "dynamic-user-group").contains(type) ? "DYNAMIC" : "RESOLVED";
            objects.put(id, new PolicyObject(id, name, type, refs, values, status, type.equals("schedule") ? PolicySchedule.panorama(entry) : null));
        }
        return id;
    }
    private static String family(String type) {
        return switch (type) {
            case "address-group" -> "address"; case "service-group" -> "service";
            case "application-group", "application-filter" -> "application"; default -> type;
        };
    }
    /**
     * Display values per object type. Never read external-list auth/* (username, password) or certificate-profile;
     * an external-list URL is stored without credentials, query string or fragment.
     */
    private static List<String> objectValues(Element entry, String type) {
        List<String> values = new ArrayList<>();
        switch (type) {
            case "application" -> {
                scalars(values, entry, "category", "subcategory", "technology", "risk", "parent-app");
                lists(values, entry, "default/port");
            }
            case "application-filter" -> {
                lists(values, entry, "category", "subcategory", "technology", "risk", "tagging/tag");
                scalars(values, entry, "evasive", "excessive-bandwidth-use", "prone-to-misuse", "is-saas", "transfers-files",
                        "tunnels-other-apps", "used-by-malware", "has-known-vulnerabilities", "pervasive", "new-appid");
            }
            case "external-list" -> {
                String listType = firstChildName(entry, "type");
                if (!listType.isEmpty()) {
                    values.add("type: " + listType);
                    String url = safeUrl(text(entry, "type/" + listType + "/url"));
                    if (!url.isEmpty()) values.add("url: " + url);
                    String recurring = firstChildName(entry, "type/" + listType + "/recurring");
                    if (!recurring.isEmpty()) values.add("recurring: " + recurring);
                    scalars(values, entry, "type/" + listType + "/expand-domain");
                }
            }
            case "region" -> {
                lists(values, entry, "address");
                scalars(values, entry, "geo-location/latitude", "geo-location/longitude");
            }
            case "dynamic-user-group" -> scalars(values, entry, "filter");
            case "profile-group" -> lists(values, entry, "virus", "spyware", "vulnerability", "url-filtering",
                    "file-blocking", "data-filtering", "wildfire-analysis");
            default -> scalars(values, entry, "ip-netmask", "ip-range", "fqdn", "dynamic/filter", "protocol/tcp/port",
                    "protocol/tcp/source-port", "protocol/udp/port", "protocol/udp/source-port", "color");
        }
        return values;
    }
    private static void scalars(List<String> values, Element entry, String... paths) {
        for (String path : paths) {
            String value = text(entry, path);
            if (!value.isEmpty()) values.add(path + ": " + value);
        }
    }
    private static void lists(List<String> values, Element entry, String... paths) {
        for (String path : paths) for (String member : members(entry, path)) values.add(path + ": " + member);
    }
    /** Scheme, host, port and path only. A value that is not a URL is kept only when it is a plain list identifier. */
    static String safeUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim();
        if (!value.contains("://")) return value.matches("[A-Za-z0-9._-]{1,128}") ? value : "";
        try {
            var uri = new java.net.URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                    + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (java.net.URISyntaxException invalid) { return ""; }
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
