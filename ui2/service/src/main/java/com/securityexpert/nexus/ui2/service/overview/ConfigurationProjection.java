package com.securityexpert.nexus.ui2.service.overview;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * The server-side twin of {@code ui2/frontend/src/screens/configurationProjection.ts}
 * (OVERVIEW_EXCEPTION_SCREEN_CONTRACT §5.1): the same rules, the same row keys, the same MEMBER-specific
 * exemptions, so the Overview's cluster DIFF count equals what the Configuration screen shows. Only the parts
 * the DIFF count needs are ported (rows: section, setting, key, value, origin); snapshot tiles stay in the
 * browser. A shared fixture test pins the two implementations together.
 */
public final class ConfigurationProjection {

    public record Row(String section, String setting, String key, String value, boolean memberSpecific) {
    }

    public record ClusterDiff(int diffCount, int settingCount, List<String> diffSections) {
    }

    private ConfigurationProjection() {
    }

    private static final Map<String, String> ACRONYMS = Map.ofEntries(
            Map.entry("dns", "DNS"), Map.entry("ntp", "NTP"), Map.entry("ssh", "SSH"), Map.entry("ssl", "SSL"),
            Map.entry("snmp", "SNMP"), Map.entry("aaa", "AAA"), Map.entry("arp", "ARP"), Map.entry("ip", "IP"),
            Map.entry("ipv4", "IPv4"), Map.entry("ipv6", "IPv6"), Map.entry("vs", "VS"), Map.entry("vsx", "VSX"),
            Map.entry("ha", "HA"), Map.entry("lldp", "LLDP"), Map.entry("rip", "RIP"), Map.entry("ospf", "OSPF"),
            Map.entry("bgp", "BGP"), Map.entry("mtu", "MTU"), Map.entry("lcd", "LCD"), Map.entry("url", "URL"),
            Map.entry("tls", "TLS"), Map.entry("nat", "NAT"), Map.entry("vlan", "VLAN"), Map.entry("id", "ID"),
            Map.entry("mgmt", "Mgmt"));

    static String titleCase(String token) {
        List<String> out = new ArrayList<>();
        for (String t : token.split("[-_]")) {
            if (t.isEmpty()) {
                continue;
            }
            String acronym = ACRONYMS.get(t.toLowerCase(Locale.ROOT));
            out.add(acronym != null ? acronym : Character.toUpperCase(t.charAt(0)) + t.substring(1));
        }
        return String.join(" ", out);
    }

    private static final class Builder {
        final List<Row> rows = new ArrayList<>();
        final Map<String, Integer> counters = new HashMap<>();

        void push(String section, String setting, String value, boolean memberSpecific) {
            String base = section + "|" + setting;
            int n = counters.merge(base, 1, Integer::sum);
            rows.add(new Row(section, setting, n == 1 ? base : base + "#" + n, value, memberSpecific));
        }
    }

    // ---------------------------------------------------------------------------------------------- Check Point

    private record CpRule(List<String> prefix, String section, int nameTokens, Function<List<String>, String> label,
            boolean member) {
    }

    private static CpRule rule(String prefix, String section, int nameTokens) {
        return new CpRule(List.of(prefix.split(" ")), section, nameTokens, null, false);
    }

    private static CpRule rule(String prefix, String section, int nameTokens, Function<List<String>, String> label) {
        return new CpRule(List.of(prefix.split(" ")), section, nameTokens, label, false);
    }

    private static final List<CpRule> CP_RULES = List.of(
            new CpRule(List.of("hostname"), "System", 0, t -> "Hostname", true),
            rule("domainname", "System", 0, t -> "Domain"),
            rule("timezone", "System", 0, t -> "Timezone"),
            rule("format", "System", 1),
            rule("dns primary", "DNS", 0, t -> "Primary DNS"),
            rule("dns secondary", "DNS", 0, t -> "Secondary DNS"),
            rule("dns tertiary", "DNS", 0, t -> "Tertiary DNS"),
            rule("dns", "DNS", 1),
            rule("ntp server primary", "NTP", 0, t -> "Primary NTP Server"),
            rule("ntp server secondary", "NTP", 0, t -> "Secondary NTP Server"),
            rule("ntp", "NTP", 1),
            rule("inactivity-timeout", "Management", 0, t -> "Inactivity Timeout"),
            rule("management", "Management", 1),
            rule("web", "Management", 1),
            rule("ssh", "Management", 1),
            rule("net-access", "Management", 1),
            rule("proxy", "Management", 0, t -> "Proxy"),
            rule("ssl", "Management", 1),
            rule("password-controls", "Password Policy", 1),
            rule("message", "Login Banner", 1),
            rule("syslog", "Logging", 1),
            rule("cluster", "High Availability", 1),
            rule("interface", "Interfaces", 2, t -> "Interface " + (t.isEmpty() ? "" : t.get(0)) + " · "
                    + titleCase(t.size() > 1 ? t.get(1) : "")),
            rule("static-route", "Routing", 1, t -> "Static Route · " + (t.isEmpty() ? "" : t.get(0))),
            rule("ospf", "Routing", 1),
            rule("rip", "Routing", 1),
            rule("bgp", "Routing", 1),
            rule("inbound-route-filter", "Routing", 1),
            rule("router-options", "Routing", 1),
            rule("routedsyslog", "Routing", 0, t -> "Routed Syslog"),
            rule("max-path-splits", "Routing", 0, t -> "Max Path Splits"),
            rule("snmp", "SNMP", 1),
            rule("aaa", "AAA", 1),
            rule("user", "Users", 1, t -> "User · " + (t.isEmpty() ? "" : t.get(0))));

    private static final Pattern CP_MEMBER_SPECIFIC = Pattern.compile("^(Interface .* · (IPv4 Address|IPv6 Address)|Hostname)$");

    public static List<Row> checkPoint(String text) {
        Builder b = new Builder();
        for (String raw : text.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.startsWith("set ")) {
                continue;
            }
            List<String> tokens = List.of(line.substring(4).strip().split("\\s+"));
            CpRule matched = null;
            for (CpRule r : CP_RULES) {
                boolean ok = r.prefix().size() <= tokens.size();
                for (int i = 0; ok && i < r.prefix().size(); i++) {
                    ok = r.prefix().get(i).equals(tokens.get(i));
                }
                if (ok) {
                    matched = r;
                    break;
                }
            }
            if (matched == null) {
                String setting = titleCase(tokens.get(0)) + (tokens.size() > 1 ? " · " + titleCase(tokens.get(1)) : "");
                String value = String.join(" ", tokens.subList(Math.min(2, tokens.size()), tokens.size()));
                b.push("Other Gaia Configuration", setting, value.isEmpty() ? "on" : value, false);
                continue;
            }
            List<String> after = tokens.subList(matched.prefix().size(), tokens.size());
            List<String> nameTokens = after.subList(0, Math.min(matched.nameTokens(), after.size()));
            String value = String.join(" ", after.subList(Math.min(matched.nameTokens(), after.size()), after.size()));
            String setting;
            if (matched.label() != null) {
                setting = matched.label().apply(nameTokens);
            } else {
                StringBuilder s = new StringBuilder(titleCase(matched.prefix().get(matched.prefix().size() - 1)));
                if (!nameTokens.isEmpty()) {
                    s.append(" · ").append(String.join(" · ", nameTokens.stream().map(ConfigurationProjection::titleCase).toList()));
                }
                setting = s.toString();
            }
            boolean member = matched.member() || CP_MEMBER_SPECIFIC.matcher(setting).matches();
            b.push(matched.section(), setting, value.isEmpty() ? "on" : value, member);
        }
        return b.rows;
    }

    // ---------------------------------------------------------------------------------------------- Palo Alto

    private record PanRule(Pattern test, String section) {
    }

    private static final List<PanRule> PAN_RULES = List.of(
            new PanRule(Pattern.compile("/deviceconfig/system/dns-setting/"), "DNS"),
            new PanRule(Pattern.compile("/deviceconfig/system/ntp-servers/"), "NTP"),
            new PanRule(Pattern.compile("/deviceconfig/system/(permitted-ip|service)/"), "Management Services"),
            new PanRule(Pattern.compile("/deviceconfig/system/(update-schedule|panorama|snmp-setting|login-banner|motd-and-banner)"), "Management"),
            new PanRule(Pattern.compile("/deviceconfig/system/"), "System"),
            new PanRule(Pattern.compile("/deviceconfig/high-availability/"), "High Availability"),
            new PanRule(Pattern.compile("/deviceconfig/setting/management/"), "Management"),
            new PanRule(Pattern.compile("/deviceconfig/setting/"), "Settings"),
            new PanRule(Pattern.compile("/mgt-config/password-complexity/"), "Password Policy"),
            new PanRule(Pattern.compile("/mgt-config/users/"), "Users"),
            new PanRule(Pattern.compile("/shared/log-settings/"), "Logging"),
            new PanRule(Pattern.compile("/network/interface/"), "Interfaces"),
            new PanRule(Pattern.compile("/network/virtual-router/"), "Routing"),
            new PanRule(Pattern.compile("/network/"), "Network Configuration"),
            new PanRule(Pattern.compile("/telemetry|/statistics-service"), "Telemetry"));

    private static final Pattern PAN_MEMBER_SPECIFIC = Pattern.compile(
            "/deviceconfig/system/(hostname|ip-address|ipv6-address)$|/high-availability/interface/[^/]+/(ip-address|ipv6-address)$|/high-availability/.*peer-ip[^/]*$");
    private static final Pattern PAN_DROPPED_PART = Pattern.compile("^(deviceconfig|entry|localhost\\.localdomain|vsys1)$");

    static String panLabel(String path, List<String> names) {
        List<String> parts = new ArrayList<>();
        for (String p : path.split("/")) {
            if (!p.isEmpty() && !p.equals("config") && !p.equals("devices") && !p.equals("response") && !p.equals("result")) {
                parts.add(p);
            }
        }
        List<String> cleaned = parts.stream().filter(p -> !PAN_DROPPED_PART.matcher(p).matches() && !names.contains(p)).toList();
        StringBuilder nameSuffix = new StringBuilder();
        for (String n : names) {
            if (!n.equals("localhost.localdomain")) {
                nameSuffix.append(" · ").append(n);
            }
        }
        String label = String.join(" · ", cleaned.subList(Math.max(0, cleaned.size() - 3), cleaned.size()).stream()
                .map(ConfigurationProjection::titleCase).toList());
        if (label.equals("Dns Setting · Servers · Primary") || label.equals("DNS Setting · Servers · Primary")) {
            return "Primary DNS";
        }
        if (label.endsWith("Servers · Secondary") && path.contains("dns-setting")) {
            return "Secondary DNS";
        }
        if (path.contains("ntp-servers/primary-ntp-server")) {
            return "Primary NTP Server";
        }
        if (path.contains("ntp-servers/secondary-ntp-server")) {
            return "Secondary NTP Server";
        }
        return (label.isEmpty() ? titleCase(parts.isEmpty() ? "" : parts.get(parts.size() - 1)) : label) + nameSuffix;
    }

    public static List<Row> paloAlto(String xml) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder db = f.newDocumentBuilder();
            db.setErrorHandler(null);
            doc = db.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            return List.of();
        }
        Builder b = new Builder();
        int[] counts = new int[4]; // interfaces, vsys, virtual routers, zones
        walk(doc.getDocumentElement(), "", List.of(), b, counts);
        b.push("Network Configuration", "VSYS", String.valueOf(counts[1]), false);
        b.push("Network Configuration", "Virtual Routers", String.valueOf(counts[2]), false);
        b.push("Network Configuration", "Zones", String.valueOf(counts[3]), false);
        b.push("Network Configuration", "Interfaces", String.valueOf(counts[0]), false);
        return b.rows;
    }

    private static final Pattern IFACE_PARENT = Pattern.compile("/network/interface/(ethernet|aggregate-ethernet|loopback|vlan|tunnel)(/units)?$");

    private static void walk(Element el, String path, List<String> names, Builder b, int[] counts) {
        String name = el.hasAttribute("name") ? el.getAttribute("name") : null;
        String tag = el.getTagName();
        String here = path + "/" + tag;
        List<String> nextNames = name != null && !name.isEmpty() ? append(names, name) : names;
        if ("entry".equals(tag)) {
            if (IFACE_PARENT.matcher(path).find()) {
                counts[0]++;
            }
            if (path.endsWith("/vsys")) {
                counts[1]++;
            }
            if (path.endsWith("/network/virtual-router")) {
                counts[2]++;
            }
            if (path.endsWith("/zone")) {
                counts[3]++;
            }
        }
        List<Element> children = new ArrayList<>();
        NodeList nodes = el.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) nodes.item(i));
            }
        }
        if (children.isEmpty()) {
            String value = el.getTextContent() == null ? "" : el.getTextContent().strip();
            if (here.contains("/network/") || here.contains("/vsys/") || here.contains("/shared/")) {
                return;
            }
            if ("[REDACTED]".equals(value)) {
                return;
            }
            String section = "Other";
            for (PanRule r : PAN_RULES) {
                if (r.test().matcher(here).find()) {
                    section = r.section();
                    break;
                }
            }
            boolean member = PAN_MEMBER_SPECIFIC.matcher(here).find();
            b.push(section, panLabel(here, nextNames), value.isEmpty() ? "(set, no value)" : value, member);
            return;
        }
        for (Element child : children) {
            walk(child, here, nextNames, b, counts);
        }
    }

    private static List<String> append(List<String> list, String value) {
        List<String> out = new ArrayList<>(list);
        out.add(value);
        return out;
    }

    // ---------------------------------------------------------------------------------------------- Cluster

    /** {@code projectCluster}: a key present on some members only, or with differing values, is a DIFF unless MEMBER-specific. */
    public static ClusterDiff cluster(Map<String, List<Row>> rowsByMember) {
        List<String> memberIds = new ArrayList<>(rowsByMember.keySet());
        Map<String, Row> first = new LinkedHashMap<>();
        Map<String, Map<String, String>> values = new HashMap<>();
        for (String member : memberIds) {
            for (Row r : rowsByMember.get(member)) {
                first.putIfAbsent(r.key(), r);
                values.computeIfAbsent(r.key(), k -> new HashMap<>()).put(member, r.value());
            }
        }
        int diffCount = 0;
        Set<String> sections = new LinkedHashSet<>();
        for (Map.Entry<String, Row> e : first.entrySet()) {
            if (e.getValue().memberSpecific()) {
                continue;
            }
            Map<String, String> v = values.get(e.getKey());
            boolean diff = v.size() != memberIds.size() || new java.util.HashSet<>(v.values()).size() > 1;
            if (diff) {
                diffCount++;
                sections.add(e.getValue().section());
            }
        }
        return new ClusterDiff(diffCount, first.size(), List.copyOf(sections));
    }
}
