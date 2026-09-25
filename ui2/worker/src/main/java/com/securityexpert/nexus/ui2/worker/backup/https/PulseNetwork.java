package com.securityexpert.nexus.ui2.worker.backup.https;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/**
 * The network part of a Pulse Secure XML export (MEASURE FIRST: the element names are logged on the first run). Read
 * only what is shaped like an interface -- an element named "*-port" or "vlan*" with an ip-address (and netmask) under
 * it -- or a route -- an element with a destination and a gateway/next hop under it. No DTD, no external entities.
 */
public final class PulseNetwork {

    public record Parsed(List<InventoryInterface> interfaces, List<InventoryRoute> routes, Map<String, Integer> elementCounts) {
    }

    private PulseNetwork() {
    }

    public static Parsed parse(String xml) {
        List<InventoryInterface> ifs = new ArrayList<>();
        List<InventoryRoute> routes = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        if (xml == null || !xml.stripLeading().startsWith("<")) {
            return new Parsed(ifs, routes, counts);
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            Element root = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
            walk(root, ifs, routes, counts);
        } catch (Exception e) {
            counts.put("#unparseable:" + e.getClass().getSimpleName(), 1);
        }
        return new Parsed(ifs, routes, counts);
    }

    private static void walk(Element e, List<InventoryInterface> ifs, List<InventoryRoute> routes, Map<String, Integer> counts) {
        String name = e.getTagName().toLowerCase(Locale.ROOT);
        counts.merge(name, 1, Integer::sum);
        if (name.endsWith("-port") || name.startsWith("vlan")) {
            Optional<String> ip = firstText(e, "ip-address");
            if (ip.isPresent() && ip.get().matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && !ip.get().equals("0.0.0.0")) {
                String label = firstText(e, "name").orElse(name);
                int len = firstText(e, "netmask").filter(m -> m.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")).map(PulseNetwork::prefix).orElse(32);
                ifs.add(new InventoryInterface(UUID.randomUUID().toString(), label, Optional.empty(),
                        name.startsWith("vlan") ? InventoryInterface.KIND_VLAN : InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UNKNOWN,
                        List.of(new InventoryAddress(UUID.randomUUID().toString(), ip.get() + "/" + len, InventoryAddress.FAMILY_IPV4,
                                InventoryAddress.ROLE_MEMBER))));
            }
        }
        Optional<String> dest = directText(e, "destination");
        Optional<String> gw = directText(e, "gateway").or(() -> directText(e, "next-hop"));
        if (dest.isPresent() && gw.isPresent() && dest.get().matches("\\d+\\.\\d+\\.\\d+\\.\\d+(/\\d+)?")) {
            String d = dest.get();
            if (!d.contains("/")) {
                d = d + "/" + directText(e, "netmask").filter(m -> m.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")).map(PulseNetwork::prefix).orElse(32);
            }
            routes.add(new InventoryRoute(UUID.randomUUID().toString(), d, gw.filter(g -> !g.equals("0.0.0.0")),
                    directText(e, "interface"), d.equals("0.0.0.0/0") ? InventoryRoute.PROTOCOL_DEFAULT : InventoryRoute.PROTOCOL_STATIC,
                    Optional.empty()));
        }
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element c) {
                walk(c, ifs, routes, counts);
            }
        }
    }

    private static Optional<String> firstText(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? Optional.empty() : Optional.of(l.item(0).getTextContent().trim()).filter(s -> !s.isEmpty());
    }

    private static Optional<String> directText(Element e, String tag) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element c && c.getTagName().equalsIgnoreCase(tag)) {
                String t = c.getTextContent().trim();
                return t.isEmpty() ? Optional.empty() : Optional.of(t);
            }
        }
        return Optional.empty();
    }

    static int prefix(String dotted) {
        int len = 0;
        for (String p : dotted.split("\\.")) {
            len += Integer.bitCount(Integer.parseInt(p) & 0xff);
        }
        return len;
    }
}
