package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.worker.inventory.ParsedInterface;

/**
 * {@link PaloAltoInterfaceParser#parse}'s result (14E PP-1, PM-2, PM-4):
 * the logical {@code ifnet/entry} interfaces grouped by their own {@code
 * vsys} leaf, the {@code hw/entry} physical ports (PM-2: "context {@code
 * physical}, no address"), and the two lookup maps the executor's own
 * PM-3 route-to-vsys attribution needs -- an interface's {@code vsys} leaf
 * and its {@code fwd} leaf (the virtual router it forwards through, {@code
 * vr:} prefix stripped). V148 (PO 2026-10-15) adds an interface's {@code zone}
 * leaf and a physical port's {@code mac} leaf, both keyed by the vendor's own
 * interface name; absent leaves are absent keys, never guesses.
 *
 * <p>V184 (path analysis A1, G2): {@code interfaceNameToRoutingContext} is the interface's virtual router on the legacy
 * routing engine only -- the one {@code show routing route} reads (A0 §5a: only one engine is in effect at a time).
 * A logical router (Advanced Routing Engine) is not recorded there, so its interfaces' routing context stays UNKNOWN
 * until a logical-router route read exists (G10). The map is persisted as {@code device_interface.routing_context};
 * {@code interfaceNameToVirtualRouter} keeps its PM-3 route attribution role unchanged.</p>
 */
public record PaloAltoInterfaceParseResult(
        Map<String, List<ParsedInterface>> interfacesByVsys,
        List<ParsedInterface> physicalPorts,
        Map<String, String> interfaceNameToVsys,
        Map<String, String> interfaceNameToVirtualRouter,
        Map<String, String> interfaceNameToZone,
        Map<String, String> portNameToMac,
        Map<String, String> interfaceNameToRoutingContext) {

    public PaloAltoInterfaceParseResult(Map<String, List<ParsedInterface>> interfacesByVsys, List<ParsedInterface> physicalPorts,
            Map<String, String> interfaceNameToVsys, Map<String, String> interfaceNameToVirtualRouter) {
        this(interfacesByVsys, physicalPorts, interfaceNameToVsys, interfaceNameToVirtualRouter, Map.of(), Map.of(), Map.of());
    }

    /** Pre-V184 shape: no routing context recorded (UNKNOWN). */
    public PaloAltoInterfaceParseResult(Map<String, List<ParsedInterface>> interfacesByVsys, List<ParsedInterface> physicalPorts,
            Map<String, String> interfaceNameToVsys, Map<String, String> interfaceNameToVirtualRouter,
            Map<String, String> interfaceNameToZone, Map<String, String> portNameToMac) {
        this(interfacesByVsys, physicalPorts, interfaceNameToVsys, interfaceNameToVirtualRouter, interfaceNameToZone,
                portNameToMac, Map.of());
    }

    public PaloAltoInterfaceParseResult {
        Objects.requireNonNull(interfacesByVsys, "interfacesByVsys");
        Objects.requireNonNull(physicalPorts, "physicalPorts");
        Objects.requireNonNull(interfaceNameToVsys, "interfaceNameToVsys");
        Objects.requireNonNull(interfaceNameToVirtualRouter, "interfaceNameToVirtualRouter");
        interfacesByVsys = new LinkedHashMap<>(interfacesByVsys);
        physicalPorts = List.copyOf(physicalPorts);
        interfaceNameToVsys = Map.copyOf(interfaceNameToVsys);
        interfaceNameToVirtualRouter = Map.copyOf(interfaceNameToVirtualRouter);
        interfaceNameToZone = interfaceNameToZone == null ? Map.of() : Map.copyOf(interfaceNameToZone);
        portNameToMac = portNameToMac == null ? Map.of() : Map.copyOf(portNameToMac);
        interfaceNameToRoutingContext = interfaceNameToRoutingContext == null ? Map.of()
                : Map.copyOf(interfaceNameToRoutingContext);
    }
}
