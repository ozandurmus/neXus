package com.securityexpert.nexus.ui2.worker.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.confirm.IdentityMismatchEvaluator;
import com.securityexpert.nexus.ui2.worker.confirm.PresentedIdentity;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointClusterVirtualInterfaceParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointClusterVirtualInterfaceParser.VirtualInterfaceAddress;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointIpAddrParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointIpRouteParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointVsidCompositeOutputSplitter;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointVsxStatParser;
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
 * the physical context and per-VSID) are executed and parsed but never
 * persisted -- 14C D-4 names no HA-role column on {@code
 * device_inventory_run}/{@code device_interface}/{@code device_route};
 * the read exists only to keep the paced session's own command sequence
 * exactly as 14D §3 lists it. The same is true of {@code
 * CheckPointHaStateParser}'s per-VSID VSLS role table (14D PR-4): it is
 * parsed off the physical {@code cphaprob stat} read for observability but
 * has no column to persist into either.</p>
 */
public final class InventoryCapabilityExecutor {

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
        ConnectSpec spec = new ConnectSpec(request.credentialRef(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(target, spec, READ_TIMEOUT);
        } catch (IllegalStateException credentialUnresolvable) {
            return new InventoryResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
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

            String ipv4 = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_IP_ADDR_SHOW_V4, vsxHost));
            String ipv6 = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_IP_ADDR_SHOW_V6, vsxHost));
            String routeOutput = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_IP_ROUTE_SHOW, vsxHost));
            execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_CPHAPROB_STAT, vsxHost));
            String vipOutput = execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF, vsxHost));

            List<InventoryContext> contexts = new ArrayList<>();
            contexts.add(new InventoryContext(InventoryContext.PHYSICAL,
                    toInventoryInterfaces(mergeVirtualAddresses(CheckPointIpAddrParser.parse(ipv4, ipv6),
                            CheckPointClusterVirtualInterfaceParser.parse(vipOutput))),
                    toInventoryRoutes(CheckPointIpRouteParser.parse(routeOutput))));

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
            }
            return new InventoryResult.Completed(contexts);
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
        String serial = firstMatch(SERIAL_TAG, identityOutput).orElse("");
        PresentedIdentity presented = new PresentedIdentity(serial, Optional.empty());
        IdentityMismatchEvaluator.Decision decision =
                IdentityMismatchEvaluator.evaluate(recordedIdentity, presented, strictRefuseEnabled);
        if (decision == IdentityMismatchEvaluator.Decision.REFUSE) {
            return new InventoryResult.IdentityMismatchRefused(
                    "presented serial does not match the recorded baseline; strict posture refused the contact");
        }

        xmlApiOutput(target, InventoryReadPlan.PAN_SHOW_HA_STATE, headers);
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
        return new InventoryResult.Completed(contexts);
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

    private String execOutput(TransportSession session, String command) {
        ExecResult result = transport.exec(session, new ExecSpec(command), READ_TIMEOUT);
        return switch (result) {
            case ExecResult.Completed completed -> completed.output();
            case ExecResult.TimedOut ignored -> "";
            case ExecResult.ChannelFailed ignored -> "";
        };
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
            case ConnectResult.HostKeyRejected rejected -> "host_key_rejected: " + rejected.reason();
            case ConnectResult.TimedOut ignored -> "timed_out";
            case ConnectResult.Authenticated ignored -> "authenticated";
        };
    }
}
