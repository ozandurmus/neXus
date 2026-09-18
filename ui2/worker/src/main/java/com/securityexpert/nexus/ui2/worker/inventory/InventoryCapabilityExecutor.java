package com.securityexpert.nexus.ui2.worker.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.confirm.IdentityMismatchEvaluator;
import com.securityexpert.nexus.ui2.worker.confirm.PresentedIdentity;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointClusterVirtualInterfaceParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointClusterVirtualInterfaceParser.VirtualInterfaceAddress;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointHaStateParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointIpAddrParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointIpRouteParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointVsidCompositeOutputSplitter;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointVsxStatParser;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoSystemInfoParser;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoHaStateParser;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoInterfaceParseResult;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoInterfaceParser;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoRouteParser;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * The inventory-collect device contact (14D CF-1..CF-4, 14C D-7):
 * {@code connect} -> the closed {@link InventoryReadPlan} -> {@code
 * disconnect}, over exactly one interactive session per Check Point device
 * (13F CL-1: never a cluster virtual address) or the XML API key dance per
 * Palo Alto firewall -- mirrors {@code worker.confirm.ConfirmCapabilityExecutor}'s
 * own connect/exec/disconnect shape.
 *
 * <p>Check Point executor order (14D §3): {@code vsx stat -v} runs first,
 * bare -- CF-4's text detection decides VSX before any other read's form
 * is chosen, so this one read cannot itself be issued through the {@code
 * vsenv 0} wrapper it is deciding whether to use for the rest. The other
 * five physical reads then run through {@link
 * InventoryReadPlan#checkPointPhysicalCommand}, wrapped on a VSX host and
 * bare otherwise (CF-2); per-VSID reads run last, one VSID at a time
 * (VS0 never re-entered), each as the three {@link
 * InventoryReadPlan#checkPointVsidSteps} composites.</p>
 *
 * <p>Identity mismatch (13F ID-M1..M4) reuses {@link
 * IdentityMismatchEvaluator} exactly as the confirm does: {@code
 * WARN_AND_CONTINUE}/{@code MATCH}/{@code NO_BASELINE} all continue to the
 * read plan; {@code REFUSE} (the strict posture, off by default) stops
 * before any read runs. This movement adds no new write path for the
 * warning itself -- there is none to reuse post-enrollment (only the
 * confirm's own {@code recordConfirmSuccess} ever writes an identity
 * baseline) -- so "warn... continue" here means the run proceeds and the
 * caller may audit the decision; a persisted, visible warning marker on an
 * already-{@code ENROLLED} device is a successor movement's own write
 * path, not invented here.</p>
 *
 * <p>The HA-role reads this plan issues ({@code cphaprob stat}, both at
 * the physical context and per-VSID) are executed at every position 14D §3
 * fixes; only the physical-context read is actually parsed ({@link
 * CheckPointHaStateParser}) -- the per-VSID reads exist solely to keep the
 * paced session's own command sequence exactly as 14D §3 lists it, since
 * {@link CheckPointHaStateParser#perVsidLocalRole()} already yields every
 * virtual system's role off that one physical read (14D PR-4). Both the
 * physical role and every per-VSID role persist into {@code
 * device_inventory_ha} (migration V17), one row per context. Palo Alto's
 * {@code show high-availability state} read ({@link PaloAltoHaStateParser})
 * persists the same way, scoped to the physical context (PAN carries no
 * per-vsys HA role).</p>
 */
public final class InventoryCapabilityExecutor {

    private static final System.Logger LOG = System.getLogger(InventoryCapabilityExecutor.class.getName());
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern API_KEY = Pattern.compile("<key>([^<]+)</key>");
    private static final Pattern SERIAL_TAG = Pattern.compile("(?is)<serial>\\s*([^<]+?)\\s*</serial>");

    private final DeviceTransport transport;
    private final PanCredentialResolver panCredentialResolver;

    public InventoryCapabilityExecutor(DeviceTransport transport, PanCredentialResolver panCredentialResolver) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.panCredentialResolver = panCredentialResolver;
    }

    public InventoryResult collect(InventoryRequest request, Optional<PresentedIdentity> recordedIdentity,
            boolean strictRefuseEnabled) {
        return switch (request.vendor()) {
            case CHECK_POINT -> collectCheckPoint(request, recordedIdentity, strictRefuseEnabled);
            case PALO_ALTO -> collectPaloAlto(request, recordedIdentity, strictRefuseEnabled);
        };
    }

    private InventoryResult collectCheckPoint(InventoryRequest request, Optional<PresentedIdentity> recordedIdentity,
            boolean strictRefuseEnabled) {
        ConnectionTarget target = request.connectionTarget()
                .orElseThrow(() -> new IllegalArgumentException("check_point inventory requires a connectionTarget"));
        long overallStart = System.currentTimeMillis();
        LOG.log(System.Logger.Level.INFO, "[INVENTORY_COLLECT_START] Check Point target={0}:{1}", target.host(), target.port());
        ConnectSpec spec = new ConnectSpec(request.credentialRef(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(target, spec, READ_TIMEOUT);
        } catch (IllegalStateException credentialUnresolvable) {
            long elapsed = System.currentTimeMillis() - overallStart;
            LOG.log(System.Logger.Level.WARNING,
                    "[INVENTORY_COLLECT_FAILED] Credential unresolvable for {0}:{1} after {2}ms: {3}",
                    target.host(), target.port(), elapsed, credentialUnresolvable.getMessage());
            return new InventoryResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            long elapsed = System.currentTimeMillis() - overallStart;
            LOG.log(System.Logger.Level.WARNING,
                    "[INVENTORY_COLLECT_FAILED] Connect failed for {0}:{1} after {2}ms: {3}",
                    target.host(), target.port(), elapsed, describeConnect(connectResult));
            return new InventoryResult.ConnectFailed(describeConnect(connectResult));
        }
        TransportSession session = authenticated.session();
        try {
            PresentedIdentity presented = new PresentedIdentity(session.presentedIdentity().orElse(""), Optional.empty());
            IdentityMismatchEvaluator.Decision decision =
                    IdentityMismatchEvaluator.evaluate(recordedIdentity, presented, strictRefuseEnabled);
            if (decision == IdentityMismatchEvaluator.Decision.REFUSE) {
                return new InventoryResult.IdentityMismatchRefused(
                        "presented identity does not match the recorded baseline; strict posture refused the contact");
            }

            // Executor order (14D §3): detect VSX first, bare, before any other read's form is chosen.
            String vsxProbeOutput = execOutput(session, InventoryReadPlan.CP_VSX_STAT);
            CheckPointVsxStatParser.VsxStatResult vsxStat = CheckPointVsxStatParser.parse(vsxProbeOutput);
            boolean vsxHost = vsxStat.vsx();
            LOG.log(System.Logger.Level.INFO,
                    "[INVENTORY_VSX_PROBE] target={0}:{1} vsx={2}, vsCount={3}",
                    target.host(), target.port(), vsxHost, vsxStat.devices().size());

            String ipv4 = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_IP_ADDR_SHOW_V4, vsxHost));
            String ipv6 = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_IP_ADDR_SHOW_V6, vsxHost));
            String routeOutput = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_IP_ROUTE_SHOW, vsxHost));
            String haStatOutput = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_CPHAPROB_STAT, vsxHost));
            String vipOutput = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF, vsxHost));

            long parseStart = System.currentTimeMillis();
            List<ParsedInterface> physicalInterfaces = CheckPointIpAddrParser.parse(ipv4, ipv6);
            List<ParsedRoute> physicalRoutes = CheckPointIpRouteParser.parse(routeOutput);
            long parseElapsed = System.currentTimeMillis() - parseStart;
            LOG.log(System.Logger.Level.INFO,
                    "[INVENTORY_PARSE] target={0}:{1} parsed in {2}ms: interfaces={3}, routes={4} (ipv4_len={5}, route_len={6})",
                    target.host(), target.port(), parseElapsed, physicalInterfaces.size(), physicalRoutes.size(), ipv4.length(), routeOutput.length());

            // Quantum Spark / Gaia Embedded or restricted Clish shell fallback
            if (physicalInterfaces.isEmpty()) {
                String clishIf = execOutput(session, "show interfaces all");
                if (clishIf.isBlank()) {
                    clishIf = execOutput(session, "show interfaces table");
                }
                if (clishIf.isBlank()) {
                    clishIf = execOutput(session, "clish -c 'show interfaces all'");
                }
                if (!clishIf.isBlank()) {
                    physicalInterfaces = parseClishInterfaces(clishIf);
                }
            }
            if (physicalRoutes.isEmpty()) {
                String clishRoutes = execOutput(session, "show route all");
                if (clishRoutes.isBlank()) {
                    clishRoutes = execOutput(session, "show route");
                }
                if (clishRoutes.isBlank()) {
                    clishRoutes = execOutput(session, "clish -c 'show route all'");
                }
                if (!clishRoutes.isBlank()) {
                    physicalRoutes = parseClishRoutes(clishRoutes);
                }
            }

            List<InventoryContext> contexts = new ArrayList<>();
            contexts.add(new InventoryContext(InventoryContext.PHYSICAL,
                    toInventoryInterfaces(mergeVirtualAddresses(physicalInterfaces,
                            CheckPointClusterVirtualInterfaceParser.parse(vipOutput))),
                    toInventoryRoutes(physicalRoutes)));

            CheckPointHaStateParser.HaState haState = CheckPointHaStateParser.parse(haStatOutput);
            List<InventoryHaFact> haFacts = new ArrayList<>();
            haFacts.add(new InventoryHaFact(UUID.randomUUID().toString(), InventoryContext.PHYSICAL, haState.role(),
                    haState.clusterMode(), InventoryHaFact.SOURCE_CP_CPHAPROB_STAT));

            List<String> vsids = vsxStat.devices().stream()
                    .map(CheckPointVsxStatParser.VsxDevice::vsid)
                    .filter(vsid -> !"0".equals(vsid))
                    .toList();
            for (String vsid : vsids) {
                List<String> steps = InventoryReadPlan.checkPointVsidSteps(vsid);
                String addrAndRouteCombined = execOutput(session, steps.get(0));
                String vsClusterIfOutput = execOutput(session, steps.get(1));
                execOutput(session, steps.get(2));
                CheckPointVsidCompositeOutputSplitter.Halves halves =
                        CheckPointVsidCompositeOutputSplitter.split(addrAndRouteCombined);
                List<ParsedInterface> vsInterfaces = mergeVirtualAddresses(
                        CheckPointIpAddrParser.parse(halves.addrOutput(), ""),
                        CheckPointClusterVirtualInterfaceParser.parse(vsClusterIfOutput));
                contexts.add(new InventoryContext(vsid,
                        toInventoryInterfaces(vsInterfaces),
                        toInventoryRoutes(CheckPointIpRouteParser.parse(halves.routeOutput()))));
                String vsidRole = haState.perVsidLocalRole().get(vsid);
                if (vsidRole != null) {
                    haFacts.add(new InventoryHaFact(UUID.randomUUID().toString(), vsid, vsidRole, Optional.empty(),
                            InventoryHaFact.SOURCE_CP_VSLS_TABLE));
                }
            }
            if (contexts.stream().allMatch(c -> c.interfaces().isEmpty() && c.routes().isEmpty())) {
                long totalElapsed = System.currentTimeMillis() - overallStart;
                LOG.log(System.Logger.Level.WARNING,
                        "[INVENTORY_COLLECT_FAILED] target={0}:{1} returned no interface or route data after {2}ms",
                        target.host(), target.port(), totalElapsed);
                return new InventoryResult.ConnectFailed("no_interfaces_or_routes_discovered: device returned no interface or route data (took " + totalElapsed + "ms)");
            }
            long totalElapsed = System.currentTimeMillis() - overallStart;
            LOG.log(System.Logger.Level.INFO,
                    "[INVENTORY_COLLECT_COMPLETE] target={0}:{1} completed in {2}ms, totalContexts={3}, totalInterfaces={4}",
                    target.host(), target.port(), totalElapsed, contexts.size(),
                    contexts.stream().mapToInt(c -> c.interfaces().size()).sum());
            return new InventoryResult.Completed(contexts, haFacts);
        } finally {
            transport.disconnect(session);
        }
    }

    private InventoryResult collectPaloAlto(InventoryRequest request, Optional<PresentedIdentity> recordedIdentity,
            boolean strictRefuseEnabled) {
        ApiTarget target = request.apiTarget()
                .orElseThrow(() -> new IllegalArgumentException("palo_alto inventory requires an apiTarget"));
        PanCredentialMaterial credential;
        try {
            credential = panCredentialResolver.resolve(request.credentialRef());
        } catch (IllegalStateException credentialUnresolvable) {
            return new InventoryResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }

        XmlApiResult keyResult = transport.xmlApiCall(target,
                new XmlApiSpec("POST", "keygen", "", "",
                        Map.of("user", credential.username(), "password", new String(credential.password())),
                        Map.of()),
                READ_TIMEOUT);
        Optional<String> apiKey = xmlOutput(keyResult).flatMap(InventoryCapabilityExecutor::extractApiKey);
        if (apiKey.isEmpty()) {
            return new InventoryResult.ConnectFailed("palo alto key generation did not return a usable key");
        }
        Map<String, String> headers = Map.of("X-PAN-KEY", apiKey.get());

        String identityOutput = xmlApiOutput(target, InventoryReadPlan.PAN_SHOW_SYSTEM_INFO, headers);
        PaloAltoSystemInfoParser.SystemInfo sysInfo = PaloAltoSystemInfoParser.parse(identityOutput);
        String serial = sysInfo.serial();
        PresentedIdentity presented = new PresentedIdentity(serial, Optional.empty());
        IdentityMismatchEvaluator.Decision decision =
                IdentityMismatchEvaluator.evaluate(recordedIdentity, presented, strictRefuseEnabled);
        if (decision == IdentityMismatchEvaluator.Decision.REFUSE) {
            return new InventoryResult.IdentityMismatchRefused(
                    "presented serial does not match the recorded baseline; strict posture refused the contact");
        }

        String haStateOutput = xmlApiOutput(target, InventoryReadPlan.PAN_SHOW_HA_STATE, headers);
        String interfaceOutput = xmlApiOutput(target, InventoryReadPlan.PAN_SHOW_INTERFACE_ALL, headers);
        String routeOutput = xmlApiOutput(target, InventoryReadPlan.PAN_SHOW_ROUTING_ROUTE, headers);

        PaloAltoInterfaceParseResult parsedInterfaces = PaloAltoInterfaceParser.parse(interfaceOutput);
        Map<String, List<ParsedRoute>> routesByVirtualRouter = PaloAltoRouteParser.parse(routeOutput);

        // PM-3: a virtual router maps to a vsys through its own interfaces' `fwd` (this
        // virtual router) and `vsys` leaves; a virtual router spanning more than one vsys is
        // shown under each, one with no interface at all is shown under `physical`.
        Map<String, java.util.Set<String>> virtualRouterToVsys = new LinkedHashMap<>();
        parsedInterfaces.interfaceNameToVirtualRouter().forEach((interfaceName, virtualRouter) -> {
            String vsys = parsedInterfaces.interfaceNameToVsys().get(interfaceName);
            if (vsys != null) {
                virtualRouterToVsys.computeIfAbsent(virtualRouter, key -> new java.util.LinkedHashSet<>()).add(vsys);
            }
        });

        Map<String, List<ParsedRoute>> routesByContext = new LinkedHashMap<>();
        routesByVirtualRouter.forEach((virtualRouter, routes) -> {
            java.util.Set<String> vsysIds = virtualRouterToVsys.get(virtualRouter);
            if (vsysIds == null || vsysIds.isEmpty()) {
                routesByContext.computeIfAbsent(InventoryContext.PHYSICAL, key -> new ArrayList<>()).addAll(routes);
            } else {
                for (String vsys : vsysIds) {
                    routesByContext.computeIfAbsent(vsys, key -> new ArrayList<>()).addAll(routes);
                }
            }
        });

        List<String> vsysIds = new ArrayList<>(parsedInterfaces.interfacesByVsys().keySet());
        for (String vsys : routesByContext.keySet()) {
            if (!InventoryContext.PHYSICAL.equals(vsys) && !vsysIds.contains(vsys)) {
                vsysIds.add(vsys);
            }
        }

        List<InventoryContext> contexts = new ArrayList<>();
        contexts.add(new InventoryContext(InventoryContext.PHYSICAL, toInventoryInterfaces(parsedInterfaces.physicalPorts()),
                toInventoryRoutes(routesByContext.getOrDefault(InventoryContext.PHYSICAL, List.of()))));
        for (String vsys : vsysIds) {
            contexts.add(new InventoryContext(vsys,
                    toInventoryInterfaces(parsedInterfaces.interfacesByVsys().getOrDefault(vsys, List.of())),
                    toInventoryRoutes(routesByContext.getOrDefault(vsys, List.of()))));
        }

        PaloAltoHaStateParser.HaState haState = PaloAltoHaStateParser.parse(haStateOutput);
        List<InventoryHaFact> haFacts = List.of(new InventoryHaFact(UUID.randomUUID().toString(),
                InventoryContext.PHYSICAL, haState.role(), haState.clusterMode(),
                InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE));
        return new InventoryResult.Completed(contexts, haFacts);
    }

    private static List<ParsedInterface> mergeVirtualAddresses(List<ParsedInterface> interfaces,
            List<VirtualInterfaceAddress> virtualAddresses) {
        Map<String, List<ParsedAddress>> byInterfaceName = new java.util.HashMap<>();
        for (VirtualInterfaceAddress vip : virtualAddresses) {
            byInterfaceName.computeIfAbsent(vip.interfaceName(), key -> new ArrayList<>())
                    .add(new ParsedAddress(vip.address(), com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress.FAMILY_IPV4,
                            com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress.ROLE_CLUSTER_VIRTUAL));
        }
        return interfaces.stream()
                .map(iface -> {
                    List<ParsedAddress> vips = byInterfaceName.get(iface.name());
                    if (vips == null || vips.isEmpty()) {
                        return iface;
                    }
                    List<ParsedAddress> merged = new ArrayList<>(iface.addresses());
                    merged.addAll(vips);
                    return new ParsedInterface(iface.name(), iface.parent(), iface.kind(), iface.state(), merged,
                            iface.vlanId());
                })
                .toList();
    }

    private static List<InventoryInterface> toInventoryInterfaces(List<ParsedInterface> parsed) {
        return parsed.stream().map(ParsedInterface::toInventoryInterface).toList();
    }

    private static List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute> toInventoryRoutes(
            List<ParsedRoute> parsed) {
        return parsed.stream().map(ParsedRoute::toInventoryRoute).toList();
    }

    private static int maskToPrefix(String mask) {
        if (mask == null || mask.isBlank()) {
            return 24;
        }
        try {
            String[] parts = mask.trim().split("\\.");
            if (parts.length != 4) {
                return 24;
            }
            int bits = 0;
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                bits += Integer.bitCount(octet & 0xFF);
            }
            return bits;
        } catch (Exception e) {
            return 24;
        }
    }

    static List<ParsedInterface> parseClishInterfaces(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Map<String, ClishInterfaceBuilder> builders = new LinkedHashMap<>();

        boolean isBlockFormat = Pattern.compile("(?im)^interface\\s+[a-zA-Z0-9_.-]+\\s*$").matcher(output).find();

        if (isBlockFormat) {
            ClishInterfaceBuilder current = null;
            for (String rawLine : output.split("\\R")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("---") || line.startsWith("Codes:")) {
                    continue;
                }

                Matcher ifHeaderMatcher = Pattern.compile("(?i)^interface\\s+([a-zA-Z0-9_.-]+)$").matcher(line);
                if (ifHeaderMatcher.find()) {
                    String name = ifHeaderMatcher.group(1);
                    current = builders.computeIfAbsent(name, ClishInterfaceBuilder::new);
                    continue;
                }

                if (current != null) {
                    Matcher stateMatcher = Pattern.compile("(?i)(?:state|status)[:\\s]+(on|up|off|down)").matcher(line);
                    if (stateMatcher.find()) {
                        String st = stateMatcher.group(1).toLowerCase();
                        current.state = ("on".equals(st) || "up".equals(st)) ? InventoryInterface.STATE_UP : InventoryInterface.STATE_DOWN;
                        continue;
                    }
                    Matcher ipMatcher = Pattern.compile("(?i)(?:ipv4-address|ip(?:v4)?\\s*address)[:\\s]+([0-9.]+)(?:/(\\d+))?").matcher(line);
                    if (ipMatcher.find()) {
                        current.ip = ipMatcher.group(1);
                        if (ipMatcher.group(2) != null) {
                            current.prefix = Integer.parseInt(ipMatcher.group(2));
                        }
                        continue;
                    }
                    Matcher maskMatcher = Pattern.compile("(?i)(?:subnet-mask|netmask|mask)[:\\s]+([0-9.]+)").matcher(line);
                    if (maskMatcher.find()) {
                        current.mask = maskMatcher.group(1);
                        continue;
                    }
                }
            }
        } else {
            for (String rawLine : output.split("\\R")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("---") || line.startsWith("Codes:")) {
                    continue;
                }
                if (line.toLowerCase().startsWith("interface") || line.toLowerCase().startsWith("name") || line.toLowerCase().startsWith("port")) {
                    continue;
                }
                String[] tokens = line.split("\\s+");
                if (tokens.length >= 2) {
                    String candidateName = tokens[0];
                    if (!candidateName.matches("^\\d{1,3}\\..*") && candidateName.matches("^[a-zA-Z0-9_.-]+$")) {
                        ClishInterfaceBuilder tb = builders.computeIfAbsent(candidateName, ClishInterfaceBuilder::new);
                        for (int i = 1; i < tokens.length; i++) {
                            String tok = tokens[i].toLowerCase();
                            if ("up".equals(tok) || "on".equals(tok)) {
                                tb.state = InventoryInterface.STATE_UP;
                            } else if ("down".equals(tok) || "off".equals(tok)) {
                                tb.state = InventoryInterface.STATE_DOWN;
                            } else if (tok.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?$")) {
                                if (tb.ip == null && !tok.startsWith("0.0.0.0")) {
                                    if (tok.contains("/")) {
                                        String[] ipAndPrefix = tok.split("/");
                                        tb.ip = ipAndPrefix[0];
                                        tb.prefix = Integer.parseInt(ipAndPrefix[1]);
                                    } else {
                                        tb.ip = tok;
                                    }
                                } else if (tb.ip != null && tb.mask == null && tok.matches("^255\\..*")) {
                                    tb.mask = tok;
                                }
                            }
                        }
                    }
                }
            }
        }

        List<ParsedInterface> result = new ArrayList<>();
        for (ClishInterfaceBuilder b : builders.values()) {
            if ("lo".equalsIgnoreCase(b.name)) {
                continue;
            }
            List<ParsedAddress> addrs = new ArrayList<>();
            if (b.ip != null && !b.ip.equals("0.0.0.0")) {
                int prefix = b.prefix > 0 ? b.prefix : (b.mask != null ? maskToPrefix(b.mask) : 24);
                addrs.add(new ParsedAddress(b.ip + "/" + prefix, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
            }
            String kind = b.name.contains(".") ? InventoryInterface.KIND_VLAN
                    : (b.name.startsWith("bond") ? InventoryInterface.KIND_BOND : InventoryInterface.KIND_PHYSICAL);
            Optional<String> parent = b.name.contains(".") ? Optional.of(b.name.split("\\.")[0]) : Optional.empty();
            Optional<Integer> vlanId = Optional.empty();
            if (b.name.contains(".")) {
                try {
                    vlanId = Optional.of(Integer.parseInt(b.name.split("\\.")[1]));
                } catch (Exception ignored) {}
            }
            result.add(new ParsedInterface(b.name, parent, kind, b.state, addrs, vlanId));
        }
        return result;
    }

    private static class ClishInterfaceBuilder {
        final String name;
        String state = InventoryInterface.STATE_UP;
        String ip;
        String mask;
        int prefix = 0;

        ClishInterfaceBuilder(String name) {
            this.name = name;
        }
    }

    static List<ParsedRoute> parseClishRoutes(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<ParsedRoute> routes = new ArrayList<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("Codes:") || line.startsWith("---") || line.toLowerCase().startsWith("route table")) {
                continue;
            }
            if (line.startsWith("127.")) {
                continue;
            }

            String proto = InventoryRoute.PROTOCOL_STATIC;
            String remaining = line;
            if (line.matches("^[CSBORKD]\\s+.*")) {
                char code = line.charAt(0);
                proto = switch (code) {
                    case 'C' -> InventoryRoute.PROTOCOL_CONNECTED;
                    case 'B' -> InventoryRoute.PROTOCOL_BGP;
                    case 'O' -> InventoryRoute.PROTOCOL_OSPF;
                    default -> InventoryRoute.PROTOCOL_STATIC;
                };
                remaining = line.substring(1).trim();
            }

            Matcher destMatcher = Pattern.compile("^(default|\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?)").matcher(remaining);
            if (!destMatcher.find()) {
                continue;
            }
            String rawDest = destMatcher.group(1);
            String destination = "default".equalsIgnoreCase(rawDest) ? "0.0.0.0/0" : rawDest;
            if (destination.startsWith("127.")) {
                continue;
            }
            if (!destination.contains("/")) {
                destination = destination.equals("0.0.0.0") ? "0.0.0.0/0" : destination + "/32";
            }

            Optional<String> nextHop = Optional.empty();
            Optional<String> ifName = Optional.empty();

            if (remaining.contains("is directly connected")) {
                proto = InventoryRoute.PROTOCOL_CONNECTED;
                Matcher connIf = Pattern.compile("is directly connected(?:,\\s*|\\s+)([a-zA-Z0-9_.-]+)").matcher(remaining);
                if (connIf.find()) {
                    ifName = Optional.of(connIf.group(1));
                }
            } else {
                Matcher viaMatcher = Pattern.compile("via\\s+([0-9.]+)(?:,\\s*([a-zA-Z0-9_.-]+))?").matcher(remaining);
                if (viaMatcher.find()) {
                    nextHop = Optional.of(viaMatcher.group(1));
                    if (viaMatcher.group(2) != null && !viaMatcher.group(2).isBlank()) {
                        ifName = Optional.of(viaMatcher.group(2));
                    }
                }
                if (ifName.isEmpty()) {
                    Matcher devMatcher = Pattern.compile("(?:dev|interface)\\s+([a-zA-Z0-9_.-]+)").matcher(remaining);
                    if (devMatcher.find()) {
                        ifName = Optional.of(devMatcher.group(1));
                    }
                }
            }

            routes.add(new ParsedRoute(destination, nextHop, ifName, proto, Optional.empty()));
        }
        return routes;
    }

    private String execOutput(TransportSession session, String command) {
        long startMs = System.currentTimeMillis();
        ExecResult result = transport.exec(session, new ExecSpec(command), READ_TIMEOUT);
        long elapsedMs = System.currentTimeMillis() - startMs;
        String output = switch (result) {
            case ExecResult.Completed completed -> {
                LOG.log(System.Logger.Level.INFO,
                        "[INVENTORY_EXEC] cmd=\"{0}\" took {1}ms (exit={2}, length={3})",
                        command, elapsedMs, completed.exitStatus(), completed.output().length());
                yield completed.output();
            }
            case ExecResult.TimedOut timedOut -> {
                LOG.log(System.Logger.Level.WARNING,
                        "[INVENTORY_EXEC_TIMEOUT] cmd=\"{0}\" TIMED OUT after {1}ms!",
                        command, elapsedMs);
                yield "";
            }
            case ExecResult.ChannelFailed failed -> {
                LOG.log(System.Logger.Level.WARNING,
                        "[INVENTORY_EXEC_FAILED] cmd=\"{0}\" failed after {1}ms: {2}",
                        command, elapsedMs, failed.reason());
                yield "";
            }
        };
        if ((command.startsWith("cphaprob") || command.startsWith("vsx") || command.contains("vsx"))
                && (output.isBlank() || output.contains("not found") || output.contains("CLISH") || output.contains("Unknown command"))) {
            LOG.log(System.Logger.Level.INFO,
                    "[INVENTORY_FALLBACK] cmd=\"{0}\" clish/empty ({1} chars); running bash fallback...",
                    command, output.length());
            long fbStart = System.currentTimeMillis();
            ExecResult fallback = transport.exec(session, new ExecSpec("bash -lc '" + command + "'"), READ_TIMEOUT);
            long fbElapsed = System.currentTimeMillis() - fbStart;
            if (fallback instanceof ExecResult.Completed c && !c.output().isBlank() && !c.output().contains("not found")) {
                LOG.log(System.Logger.Level.INFO,
                        "[INVENTORY_FALLBACK_SUCCESS] bash fallback for \"{0}\" succeeded in {1}ms (length={2})",
                        command, fbElapsed, c.output().length());
                return c.output();
            } else {
                LOG.log(System.Logger.Level.WARNING,
                        "[INVENTORY_FALLBACK_FAILED] bash fallback for \"{0}\" failed/empty in {1}ms",
                        command, fbElapsed);
            }
        }
        return output;
    }

    private String xmlApiOutput(ApiTarget target, String cmd, Map<String, String> headers) {
        XmlApiResult result = transport.xmlApiCall(target,
                new XmlApiSpec("GET", "op", "", "direct_firewall", Map.of("cmd", cmd), headers), READ_TIMEOUT);
        return xmlOutput(result).orElse("");
    }

    private static Optional<String> xmlOutput(XmlApiResult result) {
        return result instanceof XmlApiResult.Completed completed ? Optional.of(completed.body()) : Optional.empty();
    }

    private static Optional<String> extractApiKey(String body) {
        Matcher matcher = API_KEY.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String describeConnect(ConnectResult result) {
        return switch (result) {
            case ConnectResult.AuthenticationFailed failed -> "authentication_failed: " + failed.reason();
            case ConnectResult.HostKeyRejected rejected -> rejected.reason().startsWith("host_key_mismatch:")
                    ? rejected.reason()
                    : "host_key_rejected: " + rejected.reason();
            case ConnectResult.TimedOut timedOut -> timedOut.reason();
            case ConnectResult.Authenticated ignored -> "authenticated";
        };
    }
}
