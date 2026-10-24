package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedAddress;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedInterface;

/**
 * PO 2026-10-24 (docs/design/PO_DECISION_RECORD_2026_10_24_PAN_OS12_REPLACEMENT_READS.md; candidate GR-4 of
 * PAN_OS12_COMMAND_ALTERNATIVES_2026_10_21.md §3.4): on PAN-OS 12 or later a firewall's interfaces come from the
 * effective-running configuration neXus already stores, in place of {@code show interface all}. No device command.
 *
 * <p>Read (standard PAN-OS configuration schema paths, under {@code config/devices/entry}):</p>
 * <ul>
 *   <li>names, sub-interfaces, VLAN tag: {@code network/interface/{ethernet,aggregate-ethernet}/entry} and their
 *       {@code layer3|layer2/units/entry}; {@code network/interface/{vlan,loopback,tunnel}/units/entry} (an
 *       {@code entry} list directly under those containers is read the same way); {@code tag};</li>
 *   <li>static addresses: {@code layer3/ip/entry@name}, {@code layer3/ipv6/address/entry@name} (or {@code ip/...}
 *       directly for vlan / loopback / tunnel). Only a literal {@code a.b.c.d/len} or IPv6 {@code addr/len} is kept; an
 *       address-object name, DHCP or PPPoE address is not in the configuration and is not recorded (UNKNOWN);</li>
 *   <li>vsys: {@code vsys/entry/import/network/interface/member} ({@code vsys1} is shown as {@code 1}, as
 *       {@code show interface all} does); an interface no vsys imports is unassigned ({@code 0});</li>
 *   <li>zone: {@code vsys/entry/zone/entry/network/*}/member}; virtual router: {@code network/virtual-router/entry/interface/member}
 *       or, under advanced routing, {@code network/logical-router/entry/vrf/entry/interface/member} (the logical router's name);</li>
 *   <li>HA ports: {@code deviceconfig/high-availability/interface/{ha1,ha1-backup,ha2,ha2-backup}/port}.</li>
 * </ul>
 *
 * <p>Grade C (configuration intent, not runtime truth): every interface's link state is UNKNOWN and no MAC is
 * recorded. An interface named twice for a vsys, a zone or a router is left unattributed, never guessed. The document is
 * streamed and only the elements on the paths above are kept, so memory is bounded by the interface configuration, not
 * by the 10-20 MB document. Anything that is not a {@code status="success"} response with exactly one
 * {@code config/devices/entry} is {@link Optional#empty()} (unreadable).</p>
 */
public final class PaloAltoConfiguredInterfaceParser {

    /** HA link keys, in the order the HA state reports them. */
    public static final List<String> HA_LINKS = List.of("ha1", "ha1-backup", "ha2", "ha2-backup");

    /** The interfaces in {@code show interface all}'s shape, the configured HA port of each HA link, and safe counts. */
    public record Configured(PaloAltoInterfaceParseResult interfaces, Map<String, String> haPortByLink,
            int addressesNotLiteral, int ambiguousAttributions) {
    }

    private static final int MAX_KEPT_ELEMENTS = 200_000;
    private static final Pattern IPV4_CIDR = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}/\\d{1,2}$");
    private static final Pattern IPV6_CIDR = Pattern.compile("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*/\\d{1,3}$");
    private static final List<List<String>> KEPT = List.of(
            List.of("devices", "entry", "network", "interface"),
            List.of("devices", "entry", "network", "virtual-router", "entry", "interface"),
            List.of("devices", "entry", "network", "logical-router", "entry", "vrf", "entry", "interface"),
            List.of("devices", "entry", "vsys", "entry", "import", "network", "interface"),
            List.of("devices", "entry", "vsys", "entry", "zone", "entry", "network"),
            List.of("devices", "entry", "deviceconfig", "high-availability", "interface"));
    private static final List<String> TYPES = List.of("ethernet", "aggregate-ethernet", "vlan", "loopback", "tunnel");
    private static final List<String> MODES = List.of("layer3", "layer2", "virtual-wire", "tap");

    private PaloAltoConfiguredInterfaceParser() {
    }

    public static Optional<Configured> parse(InputStream effectiveRunning) {
        Document pruned = prune(effectiveRunning);
        if (pruned == null) {
            return Optional.empty();
        }
        Element root = pruned.getDocumentElement();
        Element config = "config".equals(root.getTagName()) ? root
                : "response".equals(root.getTagName()) && "success".equals(root.getAttribute("status"))
                        ? only(only(root, "result"), "config") : null;
        List<Element> devices = config == null ? List.of() : children(only(config, "devices"), "entry");
        if (devices.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(build(devices.get(0)));
    }

    private static Configured build(Element device) {
        int[] counts = new int[2];
        Map<String, String> vsysOf = attribution(device, counts, "vsys", e -> {
            List<String[]> pairs = new ArrayList<>();
            for (Element vsys : children(only(device, "vsys"), "entry")) {
                for (Element member : children(path(vsys, "import", "network", "interface"), "member")) {
                    pairs.add(new String[] {text(member), vsysId(vsys.getAttribute("name"))});
                }
            }
            return pairs;
        });
        Map<String, String> zoneOf = attribution(device, counts, "zone", e -> {
            List<String[]> pairs = new ArrayList<>();
            for (Element vsys : children(only(device, "vsys"), "entry")) {
                for (Element zone : children(only(vsys, "zone"), "entry")) {
                    for (Element mode : elements(only(zone, "network"))) {
                        for (Element member : children(mode, "member")) {
                            pairs.add(new String[] {text(member), zone.getAttribute("name")});
                        }
                    }
                }
            }
            return pairs;
        });
        Map<String, String> routerOf = attribution(device, counts, "router", e -> {
            List<String[]> pairs = new ArrayList<>();
            Element network = only(device, "network");
            for (Element router : children(only(network, "virtual-router"), "entry")) {
                for (Element member : children(only(router, "interface"), "member")) {
                    pairs.add(new String[] {text(member), router.getAttribute("name")});
                }
            }
            for (Element router : children(only(network, "logical-router"), "entry")) {
                for (Element vrf : children(only(router, "vrf"), "entry")) {
                    for (Element member : children(only(vrf, "interface"), "member")) {
                        pairs.add(new String[] {text(member), router.getAttribute("name")});
                    }
                }
            }
            return pairs;
        });

        Map<String, List<ParsedInterface>> byVsys = new LinkedHashMap<>();
        List<ParsedInterface> physicalPorts = new ArrayList<>();
        Map<String, String> nameToVsys = new LinkedHashMap<>();
        Map<String, String> nameToRouter = new LinkedHashMap<>();
        Map<String, String> nameToZone = new LinkedHashMap<>();
        Element interfaces = path(device, "network", "interface");
        for (String type : TYPES) {
            Element container = only(interfaces, type);
            List<Element> entries = new ArrayList<>(children(container, "entry"));
            if (!"ethernet".equals(type) && !"aggregate-ethernet".equals(type) && container != null) {
                // vlan / loopback / tunnel are one container whose sub-interfaces are units/entry (loopback.1, tunnel.2);
                // the container itself is an interface only when it carries an address of its own.
                List<Element> units = children(only(container, "units"), "entry");
                List<ParsedInterface> logical = new ArrayList<>();
                if (!children(only(container, "ip"), "entry").isEmpty()) {
                    logical.add(logical(type, Optional.empty(), container, counts));
                }
                for (Element unit : units) {
                    String unitName = unit.getAttribute("name").strip();
                    if (!unitName.isEmpty()) {
                        logical.add(logical(unitName, parentOf(unitName), unit, counts));
                    }
                }
                place(logical, vsysOf, routerOf, zoneOf, byVsys, nameToVsys, nameToRouter, nameToZone);
            }
            for (Element entry : entries) {
                String name = entry.getAttribute("name").strip();
                if (name.isEmpty()) {
                    continue;
                }
                boolean portType = "ethernet".equals(type);
                if (portType) {
                    physicalPorts.add(new ParsedInterface(name, Optional.empty(), InventoryInterface.KIND_PHYSICAL,
                            InventoryInterface.STATE_UNKNOWN, List.of(), Optional.empty()));
                }
                List<Element> holders = new ArrayList<>();
                if (portType || "aggregate-ethernet".equals(type)) {
                    for (String mode : MODES) {
                        Element modeElement = only(entry, mode);
                        if (modeElement != null) {
                            holders.add(modeElement);
                        }
                    }
                    if (holders.size() != 1) {
                        continue; // HA, aggregate-group member, decrypt-mirror, or no mode configured: no logical interface
                    }
                } else {
                    holders.add(entry);
                }
                Element holder = holders.get(0);
                List<ParsedInterface> logical = new ArrayList<>();
                logical.add(logical(name, Optional.empty(), holder, counts));
                for (Element unit : children(only(holder, "units"), "entry")) {
                    String unitName = unit.getAttribute("name").strip();
                    if (!unitName.isEmpty()) {
                        logical.add(logical(unitName, Optional.of(name), unit, counts));
                    }
                }
                place(logical, vsysOf, routerOf, zoneOf, byVsys, nameToVsys, nameToRouter, nameToZone);
            }
        }

        Map<String, String> haPortByLink = new LinkedHashMap<>();
        Element haInterfaces = path(device, "deviceconfig", "high-availability", "interface");
        Set<String> portNames = new HashSet<>();
        physicalPorts.forEach(p -> portNames.add(p.name()));
        for (String link : HA_LINKS) {
            String port = text(only(only(haInterfaces, link), "port"));
            if (port.isEmpty()) {
                continue;
            }
            haPortByLink.put(link, port);
            // A dedicated HA port (ha1-a, ha1-b, ...) is not under network/interface: list it as a port of its own.
            if (port.toLowerCase(Locale.ROOT).startsWith("ha") && portNames.add(port)) {
                physicalPorts.add(new ParsedInterface(port, Optional.empty(), InventoryInterface.KIND_OTHER,
                        InventoryInterface.STATE_UNKNOWN, List.of(), Optional.empty()));
            }
        }
        return new Configured(new PaloAltoInterfaceParseResult(byVsys, physicalPorts, nameToVsys, nameToRouter, nameToZone,
                Map.of()), haPortByLink, counts[0], counts[1]);
    }

    private static void place(List<ParsedInterface> logical, Map<String, String> vsysOf, Map<String, String> routerOf,
            Map<String, String> zoneOf, Map<String, List<ParsedInterface>> byVsys, Map<String, String> nameToVsys,
            Map<String, String> nameToRouter, Map<String, String> nameToZone) {
        for (ParsedInterface iface : logical) {
            if (nameToVsys.containsKey(iface.name())) {
                continue; // configured twice under two forms: the first stays, never duplicated
            }
            String vsys = vsysOf.getOrDefault(iface.name(), "0");
            byVsys.computeIfAbsent(vsys, key -> new ArrayList<>()).add(iface);
            nameToVsys.put(iface.name(), vsys);
            Optional.ofNullable(routerOf.get(iface.name())).ifPresent(r -> nameToRouter.put(iface.name(), r));
            Optional.ofNullable(zoneOf.get(iface.name())).ifPresent(z -> nameToZone.put(iface.name(), z));
        }
    }

    private static Optional<String> parentOf(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? Optional.empty() : Optional.of(name.substring(0, dot));
    }

    private static ParsedInterface logical(String name, Optional<String> parent, Element holder, int[] counts) {
        List<ParsedAddress> addresses = new ArrayList<>();
        for (Element ip : children(only(holder, "ip"), "entry")) {
            String value = ip.getAttribute("name").strip();
            if (IPV4_CIDR.matcher(value).matches()) {
                addresses.add(new ParsedAddress(value, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
            } else {
                counts[0]++;
            }
        }
        for (Element ip : children(path(holder, "ipv6", "address"), "entry")) {
            String value = ip.getAttribute("name").strip();
            if (IPV6_CIDR.matcher(value).matches()) {
                addresses.add(new ParsedAddress(value, InventoryAddress.FAMILY_IPV6, InventoryAddress.ROLE_MEMBER));
            } else {
                counts[0]++;
            }
        }
        Optional<Integer> vlan = Optional.empty();
        String tag = text(only(holder, "tag"));
        if (tag.matches("\\d{1,4}")) {
            vlan = Optional.of(Integer.parseInt(tag));
        }
        return new ParsedInterface(name, parent, kindOf(name), InventoryInterface.STATE_UNKNOWN, addresses, vlan);
    }

    /** Interface name to one value; a name given two different values is ambiguous and left out (counted). */
    private static Map<String, String> attribution(Element device, int[] counts, String what,
            java.util.function.Function<Element, List<String[]>> pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String[] pair : pairs.apply(device)) {
            String name = pair[0].strip();
            String value = pair[1] == null ? "" : pair[1].strip();
            if (name.isEmpty() || value.isEmpty() || ambiguous.contains(name)) {
                continue;
            }
            String previous = result.putIfAbsent(name, value);
            if (previous != null && !previous.equals(value)) {
                result.remove(name);
                ambiguous.add(name);
                counts[1]++;
            }
        }
        return result;
    }

    /** {@code vsys1} -> {@code 1}, as show interface all reports a vsys; any other name is kept as it is. */
    static String vsysId(String name) {
        String n = name == null ? "" : name.strip();
        return n.toLowerCase(Locale.ROOT).matches("vsys\\d+") ? n.substring(4) : n;
    }

    private static String kindOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("loopback")) {
            return InventoryInterface.KIND_LOOPBACK;
        }
        if (lower.startsWith("tunnel")) {
            return InventoryInterface.KIND_TUNNEL;
        }
        if (lower.startsWith("ae")) {
            return name.contains(".") ? InventoryInterface.KIND_SUBINTERFACE : InventoryInterface.KIND_BOND;
        }
        if (lower.startsWith("vlan")) {
            return InventoryInterface.KIND_VLAN;
        }
        if (name.contains(".")) {
            return InventoryInterface.KIND_SUBINTERFACE;
        }
        if (lower.startsWith("ethernet")) {
            return InventoryInterface.KIND_PHYSICAL;
        }
        return InventoryInterface.KIND_OTHER;
    }

    /** Streams the document, keeping only the ancestors and subtrees of {@link #KEPT}; null when it is not XML. */
    private static Document prune(InputStream input) {
        if (input == null) {
            return null;
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            java.util.Deque<Element> stack = new java.util.ArrayDeque<>();
            List<String> path = new ArrayList<>();
            int skipped = 0;
            int kept = 0;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        return null;
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        path.add(reader.getLocalName());
                        if (skipped > 0 || path.size() > 64 || !keep(path)) {
                            skipped++;
                            continue;
                        }
                        if (++kept > MAX_KEPT_ELEMENTS) {
                            return null;
                        }
                        Element element = document.createElement(reader.getLocalName());
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            element.setAttribute(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        }
                        if (stack.isEmpty()) {
                            document.appendChild(element);
                        } else {
                            stack.peek().appendChild(element);
                        }
                        stack.push(element);
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        path.remove(path.size() - 1);
                        if (skipped > 0) {
                            skipped--;
                        } else {
                            stack.pop();
                        }
                    } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                            && skipped == 0 && !stack.isEmpty()) {
                        stack.peek().appendChild(document.createTextNode(reader.getText()));
                    }
                }
            } finally {
                reader.close();
            }
            return document.getDocumentElement() == null ? null : document;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** The response envelope, then {@code config}, then the kept paths: their ancestors and everything below them. */
    private static boolean keep(List<String> path) {
        int start = path.indexOf("config");
        if (start < 0) {
            return path.size() <= 2 && ("response".equals(path.get(0)) && (path.size() == 1 || "result".equals(path.get(1))));
        }
        List<String> rest = path.subList(start + 1, path.size());
        for (List<String> kept : KEPT) {
            int n = Math.min(kept.size(), rest.size());
            if (kept.subList(0, n).equals(rest.subList(0, n))) {
                return true;
            }
        }
        return false;
    }

    private static Element only(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static Element path(Element parent, String... names) {
        Element current = parent;
        for (String name : names) {
            current = only(current, name);
        }
        return current;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        if (parent == null) {
            return found;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) {
                found.add(e);
            }
        }
        return found;
    }

    private static List<Element> elements(Element parent) {
        List<Element> found = new ArrayList<>();
        if (parent == null) {
            return found;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                found.add(e);
            }
        }
        return found;
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().strip();
    }
}
