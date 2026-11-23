package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.Ipv4Prefix;

/**
 * Does a Trace query's source or destination lie inside a stored cutover segment (ACI segmentation step 1, PO record
 * 2026-11-14 decision 5 / Q9)? The ACI programme redirects traffic of such an address through the fabric; the routed
 * path the engine computes is the path before that redirect, which the Trace does not model. A pure matcher over the
 * stored segment JSON ({@code blocks} only: {@code peerBlocks} are the far side of the redirect, not the redirected
 * segment). It answers a code, never a name, block or id.
 *
 * <p>Fail closed: a stored segment that cannot be read as IPv4 CIDR blocks is reported {@code unreadable}, never as "not
 * in a segment". A query address that is not an IPv4 host (IPv6, free text) matches nothing.</p>
 */
public final class CutoverSegmentCaveat {

    /** The caveat added to a result whose source or destination lies in a cutover segment (a code, never a value). */
    public static final String FABRIC_REDIRECT_NOT_MODELLED = "FABRIC_REDIRECT_NOT_MODELLED";
    /** The warning added when the stored segments could not be read or interpreted. */
    public static final String CUTOVER_SEGMENTS_UNREADABLE = "CUTOVER_SEGMENTS_UNREADABLE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code matched}: an address of the query lies in a readable segment block; {@code unreadable}: some segment was not readable. */
    public record Outcome(boolean matched, boolean unreadable) {
        public static final Outcome NONE = new Outcome(false, false);
        public static final Outcome UNREADABLE_ONLY = new Outcome(false, true);
    }

    private CutoverSegmentCaveat() {
    }

    /** Stored segment JSON documents against the query's source and destination. Nothing stored: {@link Outcome#NONE}. */
    public static Outcome check(List<String> segments, String source, String destination) {
        if (segments == null || segments.isEmpty()) {
            return Outcome.NONE;
        }
        List<Long> addresses = new ArrayList<>(2);
        for (String text : new String[] { source, destination }) {
            host(text).ifPresent(addresses::add);
        }
        boolean matched = false;
        boolean unreadable = false;
        for (String segment : segments) {
            Optional<List<Ipv4Prefix>> blocks = blocks(segment);
            if (blocks.isEmpty()) {
                unreadable = true;
                continue;
            }
            for (Ipv4Prefix block : blocks.get()) {
                for (long address : addresses) {
                    matched |= block.contains(address);
                }
            }
        }
        return new Outcome(matched, unreadable);
    }

    /** A query address as an IPv4 host without a prefix length; empty for IPv6, a prefix or anything else. */
    private static Optional<Long> host(String text) {
        return Ipv4Prefix.parseHost(text).filter(h -> h.length().isEmpty()).map(Ipv4Prefix.HostAddress::host);
    }

    /** The segment's {@code blocks} as prefixes; empty when the document, the field or any block is not readable. */
    private static Optional<List<Ipv4Prefix>> blocks(String segment) {
        try {
            JsonNode node = JSON.readTree(segment);
            if (node == null || !node.isObject() || !node.path("blocks").isArray() || node.path("blocks").isEmpty()) {
                return Optional.empty();
            }
            List<Ipv4Prefix> out = new ArrayList<>();
            for (JsonNode block : node.path("blocks")) {
                if (!block.isTextual()) {
                    return Optional.empty();
                }
                Optional<Ipv4Prefix.HostAddress> parsed = Ipv4Prefix.parseHost(block.asText());
                if (parsed.isEmpty() || parsed.get().length().isEmpty()) {
                    return Optional.empty();
                }
                out.add(parsed.get().prefix().orElseThrow());
            }
            return Optional.of(out);
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }
}
