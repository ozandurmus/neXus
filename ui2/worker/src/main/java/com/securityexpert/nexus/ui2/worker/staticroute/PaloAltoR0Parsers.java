package com.securityexpert.nexus.ui2.worker.staticroute;

import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.STATUS_FAIL;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.STATUS_PASS;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.STATUS_UNKNOWN;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.BeforePathObservation;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.failover.PanFailoverChecks;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoHaStateParser;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoRouteParser;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.ConfiguredRoutesParse;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.ConfiguredStaticRoute;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanEffectiveRunningResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanHaResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanPendingChangesResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.RibParse;

/**
 * Palo Alto Networks R0 static-route parsers (R0-PAN-1 .. R0-PAN-6). Moved verbatim from
 * {@link StaticRouteR0Parsers}, which keeps the public API and the result records and delegates here.
 */
final class PaloAltoR0Parsers {

    private PaloAltoR0Parsers() {
    }

    /**
     * R0-PAN-3 ({@code <check><pending-changes/></check>}): {@code no} -> PASS; {@code yes} -> FAIL
     * ({@code PENDING_CHANGES_PRESENT}); any other response -> UNKNOWN.
     */
    public static PanPendingChangesResult parsePanPendingChanges(String xml) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return new PanPendingChangesResult(STATUS_UNKNOWN, "PENDING_CHANGES_UNKNOWN");
        }
        Optional<Element> result = uniqueChild(response.get(), "result");
        if (result.isEmpty()) {
            return new PanPendingChangesResult(STATUS_UNKNOWN, "PENDING_CHANGES_UNKNOWN");
        }
        // Fail closed if <result> has child elements instead of a plain yes/no text node.
        for (Node n = result.get().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                return new PanPendingChangesResult(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED");
            }
        }
        String text = result.get().getTextContent().strip().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "no" -> new PanPendingChangesResult(STATUS_PASS, "NO_PENDING_CHANGES");
            case "yes" -> new PanPendingChangesResult(STATUS_FAIL, "PENDING_CHANGES_PRESENT");
            default -> new PanPendingChangesResult(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED");
        };
    }

    /**
     * R0-PAN-5 ({@code <show><high-availability><state/></high-availability></show>}): standalone is PASS;
     * active-passive with local state {@code active} and running-sync {@code synchronized} is PASS.
     */
    public static PanHaResult parsePanHaState(String xml) {
        if (xml == null || xml.isBlank()) {
            return new PanHaResult(STATUS_UNKNOWN, "HA_STATE_NOT_READY", "UNKNOWN", "UNKNOWN");
        }
        PaloAltoHaStateParser.HaState basic = PaloAltoHaStateParser.parse(xml);
        if (PaloAltoHaStateParser.STANDALONE.equals(basic.role())) {
            return new PanHaResult(STATUS_PASS, "STANDALONE", "STANDALONE", "not_applicable");
        }
        if (PaloAltoHaStateParser.UNKNOWN.equals(basic.role())) {
            return new PanHaResult(STATUS_UNKNOWN, "HA_STATE_NOT_READY", "UNKNOWN", "UNKNOWN");
        }
        PanFailoverChecks.State full = PanFailoverChecks.parse(xml);
        if (full.role().isEmpty()) {
            return new PanHaResult(STATUS_UNKNOWN, "HA_STATE_NOT_READY", basic.role(), "UNKNOWN");
        }
        if (!"active".equalsIgnoreCase(full.role())) {
            return new PanHaResult(STATUS_FAIL, "HA_STATE_NOT_READY", full.role(), full.runningSync());
        }
        if (!"synchronized".equalsIgnoreCase(full.runningSync())) {
            return new PanHaResult(STATUS_FAIL, "HA_CONFIG_OUT_OF_SYNC", full.role(), full.runningSync());
        }
        return new PanHaResult(STATUS_PASS, "HA_ACTIVE_SYNCHRONIZED", full.role(), full.runningSync());
    }

    /**
     * R0-PAN-4 ({@code <show><config><effective-running/></config></show>}): verifies the target VR exists under
     * legacy {@code virtual-router} (refuses {@code logical-router} with {@code ROUTING_CONTEXT_UNSUPPORTED}) and
     * checks that neither the VR nor a matching static route carries Panorama template provenance ({@code src="tpl"}).
     */
    public static PanEffectiveRunningResult parsePanEffectiveRunning(
            String xml,
            String virtualRouter,
            String targetDestinationCidr,
            String targetRouteName) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.EFFECTIVE_RUNNING_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return new PanEffectiveRunningResult(STATUS_UNKNOWN, "EFFECTIVE_RUNNING_UNAVAILABLE", false, false);
        }
        Optional<Element> result = uniqueChild(response.get(), "result");
        if (result.isEmpty()) {
            return new PanEffectiveRunningResult(STATUS_UNKNOWN, "EFFECTIVE_RUNNING_UNAVAILABLE", false, false);
        }
        // Check for ARE logical-router entry matching virtualRouter first
        NodeList logicalRouters = result.get().getElementsByTagName("logical-router");
        for (int i = 0; i < logicalRouters.getLength(); i++) {
            Element lr = (Element) logicalRouters.item(i);
            for (Element entry : directChildEntries(lr)) {
                if (virtualRouter.equals(entry.getAttribute("name"))) {
                    return new PanEffectiveRunningResult(STATUS_FAIL, "ROUTING_CONTEXT_UNSUPPORTED", false, false);
                }
            }
        }

        NodeList virtualRouters = result.get().getElementsByTagName("virtual-router");
        Element matchedVr = null;
        for (int i = 0; i < virtualRouters.getLength(); i++) {
            Element vrContainer = (Element) virtualRouters.item(i);
            for (Element entry : directChildEntries(vrContainer)) {
                if (virtualRouter.equals(entry.getAttribute("name"))) {
                    matchedVr = entry;
                    break;
                }
            }
            if (matchedVr != null) {
                break;
            }
        }
        if (matchedVr == null) {
            return new PanEffectiveRunningResult(STATUS_FAIL, "ROUTING_CONTEXT_NOT_FOUND", false, false);
        }
        if (hasTemplateSrcAttribute(matchedVr, false)) {
            return new PanEffectiveRunningResult(STATUS_FAIL, "PANORAMA_TEMPLATE_MANAGED", true, true);
        }

        NodeList staticRoutes = matchedVr.getElementsByTagName("static-route");
        for (int i = 0; i < staticRoutes.getLength(); i++) {
            Element srContainer = (Element) staticRoutes.item(i);
            if (hasTemplateSrcAttribute(srContainer, false)) {
                return new PanEffectiveRunningResult(STATUS_FAIL, "PANORAMA_TEMPLATE_MANAGED", true, true);
            }
            for (Element routeEntry : directChildEntries(srContainer)) {
                String rName = routeEntry.getAttribute("name");
                String rDest = uniqueChildText(routeEntry, "destination").orElse("");
                Optional<String> normalizedDest = normalizeDevicePrefix(rDest);
                boolean matchesTarget = (targetRouteName != null && targetRouteName.equals(rName))
                        || (normalizedDest.isPresent() && normalizedDest.get().equals(targetDestinationCidr));
                if (matchesTarget && hasTemplateSrcAttribute(routeEntry, true)) {
                    return new PanEffectiveRunningResult(STATUS_FAIL, "PANORAMA_TEMPLATE_MANAGED", true, true);
                }
            }
        }
        return new PanEffectiveRunningResult(STATUS_PASS, "LOCAL_VR_VERIFIED", true, false);
    }

    /**
     * R0-PAN-1 ({@code type=config&action=show&xpath=.../virtual-router/entry[@name='<VR>']/routing-table/ip/static-route}):
     * parses running static routes for the target VR fail-closed.
     */
    public static ConfiguredRoutesParse parsePanVrStaticRoutes(String xml) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return new ConfiguredRoutesParse(STATUS_UNKNOWN, "STATIC_ROUTE_CONFIG_UNAVAILABLE", List.of(), null);
        }
        Optional<Element> result = uniqueChild(response.get(), "result");
        if (result.isEmpty()) {
            // Empty <response status="success"/> or <result/> means zero static routes in that VR.
            return new ConfiguredRoutesParse(STATUS_PASS, "PARSED", List.of(), computeRoutesDigest(List.of()));
        }
        Element container = uniqueChild(result.get(), "static-route").orElse(result.get());
        List<Element> entries = directChildEntries(container);
        List<ConfiguredStaticRoute> routes = new ArrayList<>(entries.size());

        for (Element entry : entries) {
            String routeName = entry.getAttribute("name");
            if (routeName == null || routeName.isBlank()) {
                return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
            }
            boolean tpl = hasTemplateSrcAttribute(entry, true);
            Optional<String> rawDest = uniqueChildText(entry, "destination");
            Optional<String> destCidr = rawDest.flatMap(PaloAltoR0Parsers::normalizeDevicePrefix);
            if (destCidr.isEmpty()) {
                return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
            }
            String iface = uniqueChildText(entry, "interface").filter(s -> !s.isBlank()).orElse(null);
            Integer metric = null;
            Optional<String> rawMetric = uniqueChildText(entry, "metric");
            if (rawMetric.isPresent() && !rawMetric.get().isBlank()) {
                if (!rawMetric.get().matches("\\d{1,5}")) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                metric = Integer.parseInt(rawMetric.get());
            }
            Integer adminDist = null;
            Optional<String> rawDist = uniqueChildText(entry, "admin-dist");
            if (rawDist.isPresent() && !rawDist.get().isBlank()) {
                if (!rawDist.get().matches("\\d{1,3}")) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                adminDist = Integer.parseInt(rawDist.get());
            }

            boolean unsupported = uniqueChild(entry, "bfd").isPresent()
                    || uniqueChild(entry, "path-monitor").isPresent()
                    || uniqueChild(entry, "route-table").isPresent();
            String unsupportedDetail = unsupported ? "bfd_or_path_monitor" : null;
            String nextHopIp = "";

            Optional<Element> nexthopEl = uniqueChild(entry, "nexthop");
            if (nexthopEl.isEmpty()) {
                unsupported = true;
                unsupportedDetail = "missing_nexthop";
            } else {
                List<Element> nhChildren = childElements(nexthopEl.get());
                if (nhChildren.size() != 1 || !"ip-address".equals(nhChildren.get(0).getTagName())) {
                    unsupported = true;
                    unsupportedDetail = nhChildren.isEmpty() ? "empty_nexthop" : nhChildren.get(0).getTagName();
                } else {
                    Optional<String> nh = normalizeDeviceIpv4(nhChildren.get(0).getTextContent().strip());
                    if (nh.isEmpty()) {
                        return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                    }
                    nextHopIp = nh.get();
                }
            }

            routes.add(new ConfiguredStaticRoute(
                    destCidr.get(),
                    nextHopIp,
                    iface,
                    adminDist,
                    metric,
                    null,
                    routeName,
                    null,
                    tpl,
                    unsupported,
                    unsupportedDetail));
        }

        return new ConfiguredRoutesParse(STATUS_PASS, "PARSED", routes, computeRoutesDigest(routes));
    }

    /**
     * R0-PAN-6 ({@code <show><routing><route/></routing></show>}): delegates to {@link PaloAltoRouteParser}.
     */
    public static RibParse parsePanRib(String xml, String virtualRouter) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return new RibParse(STATUS_UNKNOWN, "RIB_UNAVAILABLE", List.of());
        }
        Map<String, List<ParsedRoute>> byVr = PaloAltoRouteParser.parse(xml);
        List<ParsedRoute> vrRoutes = byVr.getOrDefault(virtualRouter, List.of());
        if (vrRoutes.isEmpty() && byVr.isEmpty()) {
            return new RibParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of());
        }
        List<InventoryRoute> routes = new ArrayList<>(vrRoutes.size());
        for (ParsedRoute r : vrRoutes) {
            routes.add(new InventoryRoute(
                    UUID.randomUUID().toString(),
                    r.destination(),
                    r.nextHop(),
                    r.interfaceName(),
                    r.protocol(),
                    r.routeTable(),
                    r.active(),
                    r.ecmp(),
                    r.metric(),
                    r.nextHopKind()));
        }
        return new RibParse(STATUS_PASS, "PARSED", routes);
    }

    /**
     * R0-PAN-2 ({@code <test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>}):
     * parses FIB lookup fail-closed.
     */
    public static BeforePathObservation parsePanFibLookup(
            String xml,
            String representativeHost,
            List<InventoryRoute> ribRoutes) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return unknownPath(representativeHost, "PATH_LOOKUP_UNAVAILABLE");
        }
        Optional<Element> result = uniqueChild(response.get(), "result");
        if (result.isEmpty()) {
            return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
        }
        Optional<String> nhType = uniqueChildText(result.get(), "nh").map(s -> s.toLowerCase(Locale.ROOT));
        if (nhType.filter(t -> t.equals("discard") || t.equals("drop")).isPresent()) {
            return unknownPath(representativeHost, "FIB_DISCARD_ROUTE");
        }
        Optional<String> iface = uniqueChildText(result.get(), "interface").filter(s -> !s.isBlank());
        Optional<String> nhIp = uniqueChildText(result.get(), "ip").filter(s -> !s.isBlank())
                .flatMap(PaloAltoR0Parsers::normalizeDeviceIpv4);
        if (iface.isEmpty()) {
            return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
        }
        int hostInt = StaticRoutePreview.parseIpv4ToInt(representativeHost, "REPRESENTATIVE_HOST_INVALID");
        Optional<InventoryRoute> lpm = longestPrefixMatch(ribRoutes, hostInt);
        Optional<String> xmlPrefix = uniqueChildText(result.get(), "prefix")
                .or(() -> uniqueChildText(result.get(), "destination"))
                .flatMap(PaloAltoR0Parsers::normalizeDevicePrefix);

        String selectedPrefix = xmlPrefix
                .or(() -> lpm.map(InventoryRoute::destination))
                .orElse("UNKNOWN");
        if ("UNKNOWN".equals(selectedPrefix)) {
            return unknownPath(representativeHost, "PREFIX_NOT_RESOLVED");
        }
        String nextHop = nhIp.orElseGet(() -> lpm.flatMap(InventoryRoute::nextHop).orElse("CONNECTED"));
        String protocol = lpm.map(InventoryRoute::protocol).orElse(InventoryRoute.PROTOCOL_UNKNOWN);
        String digest = StaticRoutePreview.sha256Hex(selectedPrefix + "|" + nextHop + "|" + iface.get() + "|" + protocol);
        return new BeforePathObservation(
                representativeHost,
                "OBSERVED",
                "PATH_RECORDED",
                selectedPrefix,
                nextHop,
                iface.get(),
                protocol,
                digest);
    }

    private static Optional<InventoryRoute> longestPrefixMatch(List<InventoryRoute> routes, int hostIpInt) {
        if (routes == null || routes.isEmpty()) {
            return Optional.empty();
        }
        InventoryRoute best = null;
        int bestLen = -1;
        for (InventoryRoute r : routes) {
            if (r.active().isPresent() && !r.active().get()) {
                continue;
            }
            Optional<StaticRoutePreview.ParsedPrefix> parsed = parseRoutePrefix(r.destination());
            if (parsed.isEmpty()) {
                continue;
            }
            StaticRoutePreview.ParsedPrefix p = parsed.get();
            if (StaticRoutePreview.prefixContains(p.networkInt(), p.prefixLen(), hostIpInt) && p.prefixLen() > bestLen) {
                best = r;
                bestLen = p.prefixLen();
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<StaticRoutePreview.ParsedPrefix> parseRoutePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        if ("0.0.0.0/0".equals(raw) || "default".equalsIgnoreCase(raw)) {
            return Optional.of(new StaticRoutePreview.ParsedPrefix(0, 0, 0, "0.0.0.0/0", "0.0.0.0", "0.0.0.0"));
        }
        int slash = raw.indexOf('/');
        String ipStr = slash >= 0 ? raw.substring(0, slash) : raw;
        int len = 32;
        if (slash >= 0) {
            String lenStr = raw.substring(slash + 1);
            if (!lenStr.matches("\\d{1,2}")) {
                return Optional.empty();
            }
            len = Integer.parseInt(lenStr);
            if (len < 0 || len > 32) {
                return Optional.empty();
            }
        }
        Optional<String> normIp = normalizeDeviceIpv4(ipStr);
        if (normIp.isEmpty()) {
            return Optional.empty();
        }
        int ipInt = StaticRoutePreview.parseIpv4ToInt(normIp.get(), "ROUTE_PREFIX_INVALID");
        int maskInt = StaticRoutePreview.netmaskFromPrefixLen(len);
        int netInt = ipInt & maskInt;
        String netIp = StaticRoutePreview.formatIpv4(netInt);
        return Optional.of(new StaticRoutePreview.ParsedPrefix(
                netInt, len, maskInt, netIp + "/" + len, netIp, StaticRoutePreview.formatIpv4(maskInt)));
    }

    private static Optional<String> normalizeDevicePrefix(String raw) {
        return parseRoutePrefix(raw).map(StaticRoutePreview.ParsedPrefix::canonicalCidr);
    }

    private static Optional<String> normalizeDeviceIpv4(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            int ipInt = StaticRoutePreview.parseIpv4ToInt(raw.strip(), "IPV4_INVALID");
            return Optional.of(StaticRoutePreview.formatIpv4(ipInt));
        } catch (StaticRoutePreview.ValidationException e) {
            return Optional.empty();
        }
    }

    private static BeforePathObservation unknownPath(String representativeHost, String reasonCode) {
        return new BeforePathObservation(representativeHost, STATUS_UNKNOWN, reasonCode, null, null, null, null, null);
    }

    private static String computeRoutesDigest(List<ConfiguredStaticRoute> routes) {
        List<String> lines = routes.stream()
                .map(r -> String.join("|",
                        Objects.toString(r.destinationCidr(), ""),
                        Objects.toString(r.nextHopIp(), ""),
                        Objects.toString(r.interfaceName(), ""),
                        Objects.toString(r.distance(), ""),
                        Objects.toString(r.metric(), ""),
                        Objects.toString(r.priority(), ""),
                        Objects.toString(r.routeName(), ""),
                        Objects.toString(r.comment(), ""),
                        String.valueOf(r.templateManaged()),
                        String.valueOf(r.unsupportedAttribute())))
                .sorted(Comparator.naturalOrder())
                .toList();
        return StaticRoutePreview.sha256Hex(String.join("\n", lines));
    }

    private static Optional<Element> parsePanXmlResponse(String xml, int maxBytes) {
        if (xml == null || xml.isBlank() || xml.length() > maxBytes) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            return root != null && "response".equals(root.getTagName()) ? Optional.of(root) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<Element> uniqueChild(Element parent, String tagName) {
        if (parent == null) {
            return Optional.empty();
        }
        Element found = null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && tagName.equals(el.getTagName())) {
                if (found != null) {
                    return Optional.empty();
                }
                found = el;
            }
        }
        return Optional.ofNullable(found);
    }

    private static Optional<String> uniqueChildText(Element parent, String tagName) {
        return uniqueChild(parent, tagName).map(e -> e.getTextContent().strip());
    }

    private static List<Element> childElements(Element parent) {
        List<Element> list = new ArrayList<>();
        if (parent == null) {
            return list;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el) {
                list.add(el);
            }
        }
        return list;
    }

    private static List<Element> directChildEntries(Element parent) {
        List<Element> list = new ArrayList<>();
        if (parent == null) {
            return list;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && "entry".equals(el.getTagName())) {
                list.add(el);
            }
        }
        return list;
    }

    private static boolean hasTemplateSrcAttribute(Element element, boolean checkDescendants) {
        if (element == null) {
            return false;
        }
        String src = element.getAttribute("src");
        if (src != null && !src.isBlank() && src.toLowerCase(Locale.ROOT).startsWith("tpl")) {
            return true;
        }
        if (!checkDescendants) {
            return false;
        }
        NodeList children = element.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            Element child = (Element) children.item(i);
            String childSrc = child.getAttribute("src");
            if (childSrc != null && !childSrc.isBlank() && childSrc.toLowerCase(Locale.ROOT).startsWith("tpl")) {
                return true;
            }
        }
        return false;
    }
}
