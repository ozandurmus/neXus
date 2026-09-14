package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.regex.Pattern;

/**
 * The VSX per-context composite line ({@code "vsenv &lt;VSID&gt;; ip -4
 * addr show; ip -4 route show"}, 14C §3) is one {@code exec} call sent
 * over the paced interactive session -- Expert bash runs all three
 * sub-commands in order and returns one combined output buffer, exactly
 * as typing the same line at a real prompt would. This splits that one
 * buffer back into an "addr" half and a "route" half so the existing
 * {@link CheckPointIpAddrParser}/{@link CheckPointIpRouteParser} can each
 * parse their own half unchanged, using the two outputs' own distinct
 * line shapes (an {@code ip addr show} line never starts with a bare CIDR
 * or a route keyword; an {@code ip route show} line always does) rather
 * than an invented delimiter no real device would ever print.
 */
public final class CheckPointVsidCompositeOutputSplitter {

    private static final Pattern ROUTE_LINE_START = Pattern.compile(
            "^(default|blackhole|local|broadcast|\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?)\\b");

    private CheckPointVsidCompositeOutputSplitter() {
    }

    public record Halves(String addrOutput, String routeOutput) {
    }

    public static Halves split(String combined) {
        StringBuilder addr = new StringBuilder();
        StringBuilder route = new StringBuilder();
        boolean inRoutes = false;
        if (combined != null) {
            for (String line : combined.split("\\R")) {
                if (!inRoutes && ROUTE_LINE_START.matcher(line.trim()).find()) {
                    inRoutes = true;
                }
                (inRoutes ? route : addr).append(line).append('\n');
            }
        }
        return new Halves(addr.toString(), route.toString());
    }
}
