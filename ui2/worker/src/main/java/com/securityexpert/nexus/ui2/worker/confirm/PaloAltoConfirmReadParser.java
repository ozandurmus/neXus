package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Palo Alto XML API parsing for {@code show system info} (identity, gate
 * entry 3) and {@code show high-availability state} (HA/peer, gate entry
 * 4). {@code UNVERIFIED} against a real firewall (Band 3, contract §7).
 * Uses plain tag-text extraction, not a general XML parser, so no library
 * dependency is added for this movement.
 */
final class PaloAltoConfirmReadParser implements ConfirmReadParser {

    private static final Pattern HOSTNAME = tag("hostname");
    private static final Pattern MODEL = tag("model");
    private static final Pattern VERSION = tag("sw-version");
    private static final Pattern SERIAL = tag("serial");
    private static final Pattern HA_ENABLED = tag("enabled");
    private static final Pattern LOCAL_STATE = Pattern.compile(
            "(?is)<(?:local-info|local)>.*?<state>\\s*([A-Za-z-]+)\\s*</state>");
    private static final Pattern PEER_SERIAL = Pattern.compile(
            "(?is)<(?:peer-info|peer)>.*?<(?:serial-num|serial)>\\s*([^<\\s]+)\\s*</(?:serial-num|serial)>");
    private static final Pattern PEER_ADDRESS = Pattern.compile(
            "(?is)<(?:peer-info|peer)>.*?<mgmt-ip>\\s*([^<\\s]+)\\s*</mgmt-ip>");

    private static Pattern tag(String name) {
        return Pattern.compile("(?is)<" + name + ">\\s*([^<]+?)\\s*</" + name + ">");
    }

    @Override
    public PresentedIdentity presentedIdentity(String identityReadOutput, Optional<String> connectPresentedIdentity) {
        return new PresentedIdentity(firstMatch(SERIAL, identityReadOutput).orElse(""), connectPresentedIdentity);
    }

    @Override
    public ObservedFacts observedFacts(String identityReadOutput) {
        return new ObservedFacts(
                firstMatch(HOSTNAME, identityReadOutput),
                firstMatch(MODEL, identityReadOutput),
                firstMatch(VERSION, identityReadOutput),
                Optional.empty());
    }

    @Override
    public HaPeerClaim haPeerClaim(String haPeerReadOutput) {
        if (haPeerReadOutput == null) {
            return HaPeerClaim.standalone();
        }
        Optional<String> enabled = firstMatch(HA_ENABLED, haPeerReadOutput);
        boolean isMember = enabled.map(value -> value.equalsIgnoreCase("yes")).orElse(false);
        if (!isMember) {
            return HaPeerClaim.standalone();
        }
        Optional<String> peerAddr = firstMatch(PEER_ADDRESS, haPeerReadOutput).map(ip -> {
            int slash = ip.indexOf('/');
            return (slash > 0 ? ip.substring(0, slash) : ip).trim();
        });
        return new HaPeerClaim(true, firstMatch(PEER_SERIAL, haPeerReadOutput), peerAddr);
    }

    @Override
    public Optional<String> selfReferenceForPeer(String identityReadOutput) {
        return firstMatch(SERIAL, identityReadOutput);
    }

    @Override
    public Optional<String> haRole(String haPeerReadOutput) {
        if (haPeerReadOutput == null) {
            return Optional.of("STANDALONE");
        }
        boolean enabled = firstMatch(HA_ENABLED, haPeerReadOutput).map(value -> value.equalsIgnoreCase("yes"))
                .orElse(false);
        if (!enabled) {
            return Optional.of("STANDALONE");
        }
        return firstMatch(LOCAL_STATE, haPeerReadOutput);
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }
}
