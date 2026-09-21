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
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointFwGetifsParser;
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
    private static final String BATCH_TAG = "===NEXUS_SECTION:";
    private static final String BATCH_TAG_END = "===";

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

            List<String> vsids = vsxStat.devices().stream()
                    .map(CheckPointVsxStatParser.VsxDevice::vsid)
                    .filter(vsid -> !"0".equals(vsid))
                    .toList();

            List<String> vsNames = new ArrayList<>();
            for (CheckPointVsxStatParser.VsxDevice dev : vsxStat.devices()) {
                if ("0".equals(dev.vsid())) {
                    continue;
                }
                String name = dev.name();
                if (name != null && !name.isBlank() && !name.equalsIgnoreCase("vs" + dev.vsid()) && !name.equalsIgnoreCase("vs0")) {
                    vsNames.add(name + " (VSID " + dev.vsid() + ")");
                } else {
                    vsNames.add("VSID " + dev.vsid());
                }
            }
            String virtualSystemsString = vsNames.isEmpty() ? null : String.join(", ", vsNames);

            // Single-session compound batch read: all physical + VSID reads in one subshell
            Map<String, String> batchSections = Map.of();
            String batchCmd = buildCheckPointBatchCommand(vsxHost);
            String batchOutput = execOutput(session, batchCmd);
            if (!batchOutput.isBlank() && batchOutput.contains(BATCH_TAG)) {
                batchSections = parseBatchSections(batchOutput);
                LOG.log(System.Logger.Level.INFO,
                        "[INVENTORY_BATCH_SUCCESS] target={0}:{1} batch parsed {2} sections in single session",
                        target.host(), target.port(), batchSections.size());
            }

            String fwGetifsOutput = batchSections.containsKey("ifs")
                    ? batchSections.get("ifs")
                    : execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_FW_GETIFS, vsxHost));
            String routeOutput = batchSections.containsKey("route")
                    ? batchSections.get("route")
                    : execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_IP_ROUTE_SHOW, vsxHost));
            String haStatOutput = batchSections.containsKey("ha")
                    ? batchSections.get("ha")
                    : execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_CPHAPROB_STAT, vsxHost));

            long parseStart = System.currentTimeMillis();
            List<ParsedInterface> physicalInterfaces = CheckPointFwGetifsParser.parse(fwGetifsOutput);
            List<ParsedRoute> physicalRoutes = CheckPointIpRouteParser.parse(routeOutput);
            long parseElapsed = System.currentTimeMillis() - parseStart;
            LOG.log(System.Logger.Level.INFO,
                    "[INVENTORY_PARSE] target={0}:{1} parsed in {2}ms: interfaces={3}, routes={4} (ifs_len={5}, route_len={6})",
                    target.host(), target.port(), parseElapsed, physicalInterfaces.size(), physicalRoutes.size(), fwGetifsOutput.length(), routeOutput.length());

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

            // cp_cluster_vip_never_observed_in_fleet: measured live that cphaprob -a if / -a -m if return
            // nothing over a plain exec channel, on the same session where ip/route/cphaprob stat all work
            // fine without one -- while the same command run interactively (a real terminal) produces its
            // full report. Issued here as its own pty-enabled exec, not chained into the batch, so the
            // working batch reads keep their current, already-parsed, non-terminal output shape.
            //
            // Product Owner measured directly (2026-09-21): checkPointPhysicalCommand's vsenv wrap chains
            // with &&, so a device the vsx-detection step misclassifies as VSX -- vsenv 0 then genuinely
            // fails ("This is only supported on a VSX machine") -- short-circuits cphaprob entirely; it never
            // runs at all. The batch's own prefix already tolerates exactly this (vsenv ... || true; <read>);
            // this call now matches that fault-tolerant shape instead of checkPointPhysicalCommand's &&.
            String vipOutput = execOutputPty(session, faultTolerantVsenv0(InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF, vsxHost));
            List<VirtualInterfaceAddress> physicalVips = CheckPointClusterVirtualInterfaceParser.parse(vipOutput);
            LOG.log(System.Logger.Level.INFO,
                    "[INVENTORY_VIP_PARSE] target={0}:{1} vip_output_len={2} vip_addresses_found={3}",
                    target.host(), target.port(), vipOutput.length(), physicalVips.size());
            // cp_vsx_interfaces_identical_to_physical (State column): "fw getifs" carries no up/down
            // column at all (unlike the old "ip addr show" physical read it replaced). cphaprob -a
            // if's own "Interface Name: Status:" table only lists a "Required interfaces" subset
            // (measured live, 2026-09-21), so "ip -4 addr show" is read a second time here for its
            // state flags alone, covering every physical interface, not just the monitored ones --
            // its own address is never used (mergeVirtualAddresses/physicalInterfaces already carry
            // the real address from fw getifs).
            String physicalStateOnlyOutput =
                    execOutput(session, InventoryReadPlan.checkPointPhysicalCommand(InventoryReadPlan.CP_IP_ADDR_SHOW_STATE_ONLY, vsxHost));
            Map<String, String> physicalStates = new java.util.LinkedHashMap<>(
                    CheckPointClusterVirtualInterfaceParser.parseInterfaceStates(vipOutput));
            for (ParsedInterface iface : CheckPointIpAddrParser.parse(physicalStateOnlyOutput, "")) {
                if (!InventoryInterface.STATE_UNKNOWN.equals(iface.state())) {
                    physicalStates.put(iface.name(), iface.state());
                }
            }

            List<InventoryContext> contexts = new ArrayList<>();
            contexts.add(new InventoryContext(InventoryContext.PHYSICAL,
                    toInventoryInterfaces(applyInterfaceStates(mergeVirtualAddresses(physicalInterfaces, physicalVips), physicalStates)),
                    toInventoryRoutes(physicalRoutes)));

            CheckPointHaStateParser.HaState haState = CheckPointHaStateParser.parse(haStatOutput);
            List<InventoryHaFact> haFacts = new ArrayList<>();
            haFacts.add(new InventoryHaFact(UUID.randomUUID().toString(), InventoryContext.PHYSICAL, haState.role(),
                    haState.clusterMode(), InventoryHaFact.SOURCE_CP_CPHAPROB_STAT));

            boolean clusterMember = !"STANDALONE".equals(haState.role());
            for (String vsid : vsids) {
                // cp_vsx_interfaces_identical_to_physical: measured live (Product Owner, 2026-09-21) that
                // chaining multiple "vsenv <vsid> ...; <read>;" segments for different VSIDs inside one
                // shared bash -lc process (the batch) does not reliably re-scope every later read to its
                // own VSID. Issuing one standalone "bash -lc 'vsenv <vsid> && ...'" process per VSID (as
                // the Product Owner's own manual reproduction does) fixes that, but on a genuine ClusterXL
                // member "fw getifs"/"ip addr show" itself was then measured, still live by the Product
                // Owner, to report an internal VSX addressing scheme for a virtual system, not its real
                // configured address ("sanal IP dönüyor"): "cphaprob -a if", vsenv-scoped, is what actually
                // reports that virtual system's real, distinct cluster-interface addresses on a cluster
                // member. A standalone (non-clustered) VSX gateway has no such distinction -- cphaprob
                // reports no cluster there -- so it keeps using "fw getifs" as measured earlier.
                List<String> steps = InventoryReadPlan.checkPointVsidSteps(vsid);
                String addrAndRouteCombined = execOutput(session, steps.get(0));
                List<ParsedInterface> vsInterfaces;
                CheckPointVsidCompositeOutputSplitter.Halves halves =
                        CheckPointVsidCompositeOutputSplitter.split(addrAndRouteCombined);
                if (clusterMember) {
                    String clusterIfOutput = execOutput(session, steps.get(1));
                    // cphaprob's own status table only lists a "Required interfaces" subset (Product
                    // Owner measured live, 2026-09-21); "ip -4 addr show" still carries a real
                    // up/down flag for every interface, so it is read a second time here for that
                    // flag alone -- its address is never used (that is the exact value measured
                    // wrong on a VSX cluster member).
                    String stateOnlyOutput = execOutput(session, InventoryReadPlan.checkPointVsidStateRead(vsid));
                    Map<String, String> statesByName = new java.util.LinkedHashMap<>(
                            CheckPointClusterVirtualInterfaceParser.parseInterfaceStates(clusterIfOutput));
                    for (ParsedInterface iface : CheckPointIpAddrParser.parse(stateOnlyOutput, "")) {
                        if (!InventoryInterface.STATE_UNKNOWN.equals(iface.state())) {
                            statesByName.put(iface.name(), iface.state());
                        }
                    }
                    vsInterfaces = toParsedInterfaces(CheckPointClusterVirtualInterfaceParser.parse(clusterIfOutput), statesByName);
                } else {
                    vsInterfaces = CheckPointFwGetifsParser.parse(halves.addrOutput());
                }
                execOutput(session, steps.get(2));
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
            return new InventoryResult.Completed(contexts, haFacts, Optional.ofNullable(virtualSystemsString));
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
            String failureReason = keyResult instanceof XmlApiResult.Failed failed
                    ? "palo alto key generation failed: " + failed.reason()
                    : "palo alto key generation did not return a usable key";
            return new InventoryResult.ConnectFailed(failureReason);
        }
        Map<String, String> headers = Map.of("X-PAN-KEY", apiKey.get());

        String identityOutput = xmlApiOutput(target, InventoryReadPlan.PAN_SHOW_SYSTEM_INFO, headers);
        if (identityOutput == null || identityOutput.isBlank() || isXmlError(identityOutput)) {
            return new InventoryResult.ConnectFailed("palo alto show system info failed");
        }
        PaloAltoSystemInfoParser.SystemInfo sysInfo = PaloAltoSystemInfoParser.parse(identityOutput);
        String serial = sysInfo.serial();
        if (serial == null || serial.isBlank()) {
            return new InventoryResult.ConnectFailed("palo alto show system info returned empty serial");
        }
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

        List<ParsedInterface> physicalInterfaces = new ArrayList<>(parsedInterfaces.physicalPorts());
        if (parsedInterfaces.interfacesByVsys().containsKey("0")) {
            physicalInterfaces.addAll(parsedInterfaces.interfacesByVsys().get("0"));
        }

        List<String> vsysIds = new ArrayList<>();
        for (String vsys : parsedInterfaces.interfacesByVsys().keySet()) {
            if (isValidPanVsys(vsys) && !vsysIds.contains(vsys)) {
                vsysIds.add(vsys);
            }
        }
        for (String vsys : routesByContext.keySet()) {
            if (isValidPanVsys(vsys) && !vsysIds.contains(vsys)) {
                vsysIds.add(vsys);
            }
        }

        List<InventoryContext> contexts = new ArrayList<>();
        contexts.add(new InventoryContext(InventoryContext.PHYSICAL, toInventoryInterfaces(physicalInterfaces),
                toInventoryRoutes(routesByContext.getOrDefault(InventoryContext.PHYSICAL, List.of()))));
        for (String vsys : vsysIds) {
            contexts.add(new InventoryContext(vsys,
                    toInventoryInterfaces(parsedInterfaces.interfacesByVsys().getOrDefault(vsys, List.of())),
                    toInventoryRoutes(routesByContext.getOrDefault(vsys, List.of()))));
        }

        if (contexts.stream().allMatch(c -> c.interfaces().isEmpty() && c.routes().isEmpty())) {
            return new InventoryResult.ConnectFailed("no_interfaces_or_routes_discovered: device returned no interface or route data");
        }

        PaloAltoHaStateParser.HaState haState = PaloAltoHaStateParser.parse(haStateOutput);
        List<InventoryHaFact> haFacts = List.of(new InventoryHaFact(UUID.randomUUID().toString(),
                InventoryContext.PHYSICAL, haState.role(), haState.clusterMode(),
                InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE));

        Map<String, java.util.Set<String>> vsysToVirtualRouters = new LinkedHashMap<>();
        parsedInterfaces.interfaceNameToVsys().forEach((ifName, vsys) -> {
            String vr = parsedInterfaces.interfaceNameToVirtualRouter().get(ifName);
            if (vr != null && !vr.isBlank()) {
                vsysToVirtualRouters.computeIfAbsent(vsys, k -> new java.util.LinkedHashSet<>()).add(vr);
            }
        });
        virtualRouterToVsys.forEach((vr, vsysSet) -> {
            for (String vsys : vsysSet) {
                vsysToVirtualRouters.computeIfAbsent(vsys, k -> new java.util.LinkedHashSet<>()).add(vr);
            }
        });

        List<String> panVsNames = new ArrayList<>();
        for (String vsys : vsysIds) {
            String vsysLabel = vsys.toLowerCase().startsWith("vsys") ? vsys.toLowerCase() : "vsys" + vsys;
            java.util.Set<String> vrs = vsysToVirtualRouters.getOrDefault(vsys, java.util.Set.of());
            String vrDisplay = String.join(", ", vrs);
            String displayName = vrDisplay.isEmpty() ? vsysLabel : vrDisplay + " (" + vsysLabel + ")";
            panVsNames.add(displayName);
        }
        String virtualSystemsString = panVsNames.isEmpty() ? null : String.join(", ", panVsNames);

        return new InventoryResult.Completed(contexts, haFacts, Optional.ofNullable(virtualSystemsString));
    }

    private static boolean isValidPanVsys(String vsys) {
        if (vsys == null || vsys.isBlank()) {
            return false;
        }
        if (InventoryContext.PHYSICAL.equalsIgnoreCase(vsys)
                || "0".equals(vsys)
                || "ha".equalsIgnoreCase(vsys)
                || "N/A".equalsIgnoreCase(vsys)) {
            return false;
        }
        try {
            return Integer.parseInt(vsys) > 0;
        } catch (NumberFormatException e) {
            return vsys.toLowerCase().startsWith("vsys");
        }
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

    /** cp_vsx_interfaces_identical_to_physical: a cluster member's per-VSID interfaces, sourced from
     * {@code cphaprob -a if}'s own "Virtual cluster interfaces" section rather than {@code fw getifs}
     * (measured live to return an internal VSX addressing scheme on a cluster member, not the real
     * configured one). That section carries no netmask and no up/down state, so the address is recorded
     * bare and the state {@link InventoryInterface#STATE_UNKNOWN}, same fail-closed treatment as {@code
     * fw getifs} on a standalone gateway. */
    private static List<ParsedInterface> toParsedInterfaces(List<VirtualInterfaceAddress> clusterInterfaces,
            Map<String, String> interfaceStates) {
        return clusterInterfaces.stream()
                .map(vip -> new ParsedInterface(vip.interfaceName(), Optional.empty(),
                        CheckPointFwGetifsParser.kindOf(vip.interfaceName()),
                        interfaceStates.getOrDefault(vip.interfaceName(), InventoryInterface.STATE_UNKNOWN),
                        List.of(new ParsedAddress(vip.address(), InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)),
                        Optional.empty()))
                .toList();
    }

    /** Overrides each interface's state from {@code states} by name when present, leaving it as
     * parsed (typically {@code unknown}, since {@code fw getifs} carries no state column) otherwise. */
    private static List<ParsedInterface> applyInterfaceStates(List<ParsedInterface> interfaces, Map<String, String> states) {
        return interfaces.stream()
                .map(iface -> {
                    String state = states.get(iface.name());
                    if (state == null) {
                        return iface;
                    }
                    return new ParsedInterface(iface.name(), iface.parent(), iface.kind(), state, iface.addresses(),
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
        ExecResult result;
        try {
            result = transport.exec(session, new ExecSpec(command), READ_TIMEOUT);
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[INVENTORY_EXEC_EXCEPTION] cmd=\"{0}\" threw exception after {1}ms: {2}",
                    command, elapsedMs, e.getMessage());
            return "";
        }
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

    /** cp_cluster_vip_never_observed_in_fleet: {@code checkPointPhysicalCommand}'s own {@code vsenv 0 &&
     * <read>} wrap short-circuits {@code <read>} entirely whenever vsenv fails -- including when vsx-detection
     * misclassifies a plain gateway as VSX, measured live. Mirrors the batch's own already-fault-tolerant
     * {@code vsenv 0 >/dev/null 2>&1 || true; <read>} shape instead. */
    private static String faultTolerantVsenv0(String read, boolean vsxHost) {
        return vsxHost ? "bash -lc 'vsenv 0 >/dev/null 2>&1 || true; " + read + "'" : read;
    }

    /** cp_cluster_vip_never_observed_in_fleet: identical to {@link #execOutput}, except the exec channel
     * requests a pseudo-terminal -- some vendor report commands (measured: cphaprob's cluster-interface read)
     * produce nothing at all without one. No bash-lc fallback here: a pty-requiring command that still fails
     * under a pty is not helped by a plain, non-pty bash retry. */
    private String execOutputPty(TransportSession session, String command) {
        long startMs = System.currentTimeMillis();
        ExecResult result;
        try {
            result = transport.exec(session, new ExecSpec(command, true), READ_TIMEOUT);
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            LOG.log(System.Logger.Level.WARNING,
                    "[INVENTORY_EXEC_PTY_EXCEPTION] cmd=\"{0}\" threw exception after {1}ms: {2}",
                    command, elapsedMs, e.getMessage());
            return "";
        }
        long elapsedMs = System.currentTimeMillis() - startMs;
        return switch (result) {
            case ExecResult.Completed completed -> {
                LOG.log(System.Logger.Level.INFO,
                        "[INVENTORY_EXEC_PTY] cmd=\"{0}\" took {1}ms (exit={2}, length={3})",
                        command, elapsedMs, completed.exitStatus(), completed.output().length());
                yield completed.output();
            }
            case ExecResult.TimedOut timedOut -> {
                LOG.log(System.Logger.Level.WARNING,
                        "[INVENTORY_EXEC_PTY_TIMEOUT] cmd=\"{0}\" TIMED OUT after {1}ms!", command, elapsedMs);
                yield "";
            }
            case ExecResult.ChannelFailed failed -> {
                LOG.log(System.Logger.Level.WARNING,
                        "[INVENTORY_EXEC_PTY_FAILED] cmd=\"{0}\" failed after {1}ms: {2}",
                        command, elapsedMs, failed.reason());
                yield "";
            }
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

    private static boolean isXmlError(String xml) {
        return xml != null && (xml.contains("status=\"error\"") || xml.contains("status='error'"));
    }

    /**
     * cp_vsx_interfaces_identical_to_physical: {@code vsenv} is a shell function defined by the Expert
     * profile, not a binary -- it only exists in a login shell, which is why this whole batch is wrapped
     * in one {@code bash -lc '...'}. This batch now only carries the physical-context reads: chaining
     * multiple {@code vsenv <vsid>} context switches for DIFFERENT VSIDs inside that same shared shell
     * process was measured live (Product Owner, 2026-09-21) to not reliably re-scope every later read to
     * its own VSID. Per-VSID reads run instead as their own standalone {@code bash -lc} process each
     * (see {@link InventoryReadPlan#checkPointVsidSteps}), matching the Product Owner's own manual
     * reproduction, which gets correct, distinct output per VSID every time.
     */
    static String buildCheckPointBatchCommand(boolean vsxHost) {
        StringBuilder sb = new StringBuilder();
        String prefix = vsxHost ? "vsenv 0 >/dev/null 2>&1 || true; " : "";
        sb.append("echo \"===NEXUS_SECTION:ifs===\"; ").append(prefix).append(InventoryReadPlan.CP_FW_GETIFS).append("; ");
        sb.append("echo \"===NEXUS_SECTION:route===\"; ").append(prefix).append(InventoryReadPlan.CP_IP_ROUTE_SHOW).append("; ");
        sb.append("echo \"===NEXUS_SECTION:ha===\"; ").append(prefix).append(InventoryReadPlan.CP_CPHAPROB_STAT).append("; ");
        sb.append("echo \"===NEXUS_SECTION:vip===\"; ").append(prefix).append(InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF).append("; ");
        sb.append("echo \"===NEXUS_SECTION:END===\"");
        return "bash -lc '" + sb + "'";
    }

    static Map<String, String> parseBatchSections(String output) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (output == null || !output.contains(BATCH_TAG)) {
            return sections;
        }
        String[] parts = output.split(Pattern.quote(BATCH_TAG));
        for (String part : parts) {
            int endTag = part.indexOf(BATCH_TAG_END);
            if (endTag > 0) {
                String sectionName = part.substring(0, endTag).trim();
                String content = part.substring(endTag + BATCH_TAG_END.length()).trim();
                sections.put(sectionName, content);
            }
        }
        return sections;
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
