package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

/**
 * Cisco ASA {@code show license all} (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 2): one
 * {@code SMART_LICENSING} item labelled {@code Smart Licensing}. Smart Agent layout: the {@code Status:} line under
 * {@code Registration:} and the one under {@code License Authorization:}. Module layout: {@code Compliance status:} and
 * {@code Overall licensed status:}. Nothing else is read (account, UDI and hostname lines never).
 *
 * <p>State, in this order: EVAL_EXPIRED (authorization says "eval expired", with the date after "on" when present),
 * UNREGISTERED, OUT_OF_COMPLIANCE, EXPIRED (authorization expired), NOT_APPLICABLE (standby), VALID (authorized or in
 * compliance), else UNKNOWN. The detail is the status words only.</p>
 */
public final class AsaSmartLicensingParser {

    public static final String READ_KIND = "asa_show_license_all";
    private static final String LABEL = "Smart Licensing";
    private static final Pattern ON_DATE = Pattern.compile("(?i)\\bon ([A-Za-z]{3} \\d{1,2} \\d{4})\\b");

    private AsaSmartLicensingParser() {
    }

    /** Never throws. */
    public static LicenseParse parse(String raw) {
        try {
            return doParse(raw);
        } catch (RuntimeException | StackOverflowError e) {
            return raw == null || raw.isBlank() ? LicenseParse.of(raw, List.of(), 0) : LicenseParse.unparsed();
        }
    }

    private static LicenseParse doParse(String raw) {
        String registration = null;
        String authorization = null;
        String compliance = null;
        String overall = null;
        String section = "";
        for (String line : LifecycleText.lines(raw)) {
            String t = LifecycleText.collapse(line);
            if (t.isEmpty()) {
                continue;
            }
            int colon = t.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = t.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = t.substring(colon + 1).strip();
            if (value.isEmpty()) {
                section = key;
                continue;
            }
            if (key.equals("status") && section.equals("registration") && registration == null) {
                registration = value;
            } else if (key.equals("status") && section.equals("license authorization") && authorization == null) {
                authorization = value;
            } else if (key.equals("compliance status") && compliance == null) {
                compliance = value;
            } else if (key.equals("overall licensed status") && overall == null) {
                overall = value;
            }
        }
        boolean agent = registration != null || authorization != null;
        boolean module = compliance != null || overall != null;
        if (!agent && !module) {
            return LicenseParse.of(raw, List.of(), 0);
        }
        String state;
        LocalDate expires = null;
        String detail;
        if (agent) {
            String reg = registration == null ? "" : registration.toUpperCase(Locale.ROOT);
            String auth = authorization == null ? "" : authorization.toUpperCase(Locale.ROOT);
            if (auth.contains("EVAL EXPIRED")) {
                state = "EVAL_EXPIRED";
                Matcher on = ON_DATE.matcher(authorization);
                if (on.find()) {
                    expires = LifecycleText.asaDate(on.group(1));
                }
            } else if (reg.startsWith("UNREGISTERED")) {
                state = "UNREGISTERED";
            } else if (auth.contains("OUT OF COMPLIANCE")) {
                state = "OUT_OF_COMPLIANCE";
            } else if (auth.contains("EXPIRED")) {
                state = "EXPIRED";
            } else if (auth.startsWith("AUTHORIZED")) {
                state = "VALID";
            } else {
                state = "UNKNOWN";
            }
            detail = words("Registration=", registration) + (registration != null && authorization != null ? ", " : "")
                    + words("Authorization=", authorization);
        } else {
            String comp = compliance == null ? "" : compliance.toUpperCase(Locale.ROOT);
            String all = overall == null ? "" : overall.toUpperCase(Locale.ROOT);
            if (all.contains("STANDBY")) {
                state = "NOT_APPLICABLE";
            } else if (comp.contains("OUT OF COMPLIANCE")) {
                state = "OUT_OF_COMPLIANCE";
            } else if (comp.contains("IN COMPLIANCE") || all.startsWith("AUTHORIZED")) {
                state = "VALID";
            } else {
                state = "UNKNOWN";
            }
            detail = words("Compliance=", compliance) + (compliance != null && overall != null ? ", " : "") + words("Overall=", overall);
        }
        LicenseItem item = new LicenseItem(READ_KIND, "SMART_LICENSING", LABEL, state, expires, LifecycleText.detail(detail), null,
                "DIRECT_DEVICE", null);
        return new LicenseParse(ParseOutcome.COLLECTED, List.of(item), 0);
    }

    /** The status words of a value: letters, spaces and hyphens up to the first bracket, "on <date>" or digit. */
    private static String words(String prefix, String value) {
        if (value == null) {
            return "";
        }
        String s = value;
        int paren = s.indexOf('(');
        if (paren >= 0) {
            s = s.substring(0, paren);
        }
        Matcher on = Pattern.compile("(?i)\\s+on\\s+[A-Za-z]{3}\\s.*").matcher(s);
        s = on.replaceFirst("");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < 60; i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ' ' || c == '-') {
                out.append(c);
            } else {
                break;
            }
        }
        String cleaned = out.toString().strip();
        return cleaned.isEmpty() ? "" : prefix + cleaned;
    }
}
