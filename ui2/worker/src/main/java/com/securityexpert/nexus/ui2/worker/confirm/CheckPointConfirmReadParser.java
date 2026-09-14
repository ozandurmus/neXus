package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check Point Gaia parsing for {@code show version all}
 * (identity, gate entry 1) and {@code cphaprob stat} (HA/peer, gate entry
 * 2). {@code UNVERIFIED} against a real gateway (Band 3, contract §7) --
 * bound here at one site, corrected here alone if a real run disagrees.
 */
final class CheckPointConfirmReadParser implements ConfirmReadParser {

    private static final Pattern HOSTNAME = Pattern.compile("(?im)^\\s*Host\\s*[Nn]ame:?\\s*(\\S+)\\s*$");
    private static final Pattern VERSION = Pattern.compile("(?im)^\\s*Product\\s+version\\s+(.+?)\\s*$");
    private static final Pattern MODEL = Pattern.compile("(?im)^\\s*Appliance\\s+[Nn]ame:?\\s*(.+?)\\s*$");
    private static final Pattern NOT_A_MEMBER = Pattern.compile("(?i)not\\s+(enabled|installed|a cluster member)");
    // UNVERIFIED (gate document status line): the real cphaprob stat member-row shape is unmeasured
    // (Band 3). This binds one plausible reading -- "<member identifier> ... <state>" -- at the one
    // site the gate document names for a later correction; a non-matching real format degrades to
    // UNKNOWN (Optional.empty()), never a thrown exception.
    private static final Pattern MEMBER_LINE = Pattern.compile("(?im)^\\s*(\\S+)\\D*?(ACTIVE|STANDBY|READY|DOWN)\\s*$");

    @Override
    public PresentedIdentity presentedIdentity(String identityReadOutput, Optional<String> connectPresentedIdentity) {
        String fingerprint = connectPresentedIdentity.orElse("");
        return new PresentedIdentity(fingerprint, firstMatch(HOSTNAME, identityReadOutput));
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
        if (haPeerReadOutput == null || NOT_A_MEMBER.matcher(haPeerReadOutput).find()) {
            return HaPeerClaim.standalone();
        }
        // cphaprob stat lists every member including self; the first row is read as self (haRole,
        // above), the second distinct row -- when present -- as the one peer. A management address is
        // never carried by this read (PF-4), so peerManagementAddress always stays UNKNOWN here.
        java.util.List<String> members = memberIdentifiers(haPeerReadOutput);
        Optional<String> peer = members.size() > 1 ? Optional.of(members.get(1)) : Optional.empty();
        return new HaPeerClaim(true, peer, Optional.empty());
    }

    private static java.util.List<String> memberIdentifiers(String haPeerReadOutput) {
        java.util.List<String> members = new java.util.ArrayList<>();
        Matcher matcher = MEMBER_LINE.matcher(haPeerReadOutput);
        while (matcher.find()) {
            members.add(matcher.group(1));
        }
        return members;
    }

    @Override
    public Optional<String> selfReferenceForPeer(String identityReadOutput) {
        return firstMatch(HOSTNAME, identityReadOutput);
    }

    @Override
    public Optional<String> haRole(String haPeerReadOutput) {
        if (haPeerReadOutput == null || NOT_A_MEMBER.matcher(haPeerReadOutput).find()) {
            return Optional.of("STANDALONE");
        }
        Matcher matcher = MEMBER_LINE.matcher(haPeerReadOutput);
        return matcher.find() ? Optional.ofNullable(matcher.group(2)) : Optional.empty();
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
