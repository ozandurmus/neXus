package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line shapes of the system-log template census, over <em>masked</em> lines only: the per-source header parse
 * (what is the message, what is the level field), the severity class, the closed hardware/kernel marker set and the
 * normalisation that turns a message into the text the miner sees (numbers, hex, identifiers, addresses and masked
 * tokens collapse to {@link TemplateMiner#WILDCARD}).
 *
 * <p>Source shapes are the measured ones of {@code LineTimes} (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 4.7):
 * {@code cp_messages} {@code <Mon> <DD> <HH:MM:SS> [<YYYY>] <host> <prog>[pid]: <text>}; the elg sources
 * {@code [<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>] <text>}; {@code cp_fwk_elg}
 * {@code [<time>][fw4_<n>];[cpu_<n>];[<conn>] [<level>]: <text>}; {@code pan_system} key=value with
 * {@code severity=}, {@code subtype=}, {@code eventid=} and a quoted {@code opaque=} text. A line that does not match
 * its source's header is <em>unparsed</em> (a continuation line): it is counted and not mined. The level is read only
 * where the family carries one, never inferred from message words.</p>
 */
public final class CensusLineShapes {

    /** Severity class of a line, ordered from least to most severe; {@code UNKNOWN} when the family has no level field here. */
    public enum Severity {
        UNKNOWN, INFO, WARNING, ERROR, CRITICAL;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Severity max(Severity a, Severity b) {
            return a.ordinal() >= b.ordinal() ? a : b;
        }

        public boolean atLeastError() {
            return ordinal() >= ERROR.ordinal();
        }
    }

    /** The closed hardware/kernel marker set. */
    public enum Marker {
        EDAC_CE, EDAC_UE, MCE_HARDWARE_ERROR, KERNEL_PANIC, IO_ERROR, READONLY_REMOUNT, OOM_KILLER, BOOT;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A parsed line: the message the miner sees (not yet normalised) and its severity class. */
    public record Parsed(String message, Severity severity) {
    }

    private static final Pattern MESSAGES = Pattern.compile(
            "^(?:<(\\d{1,3})>)?[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\s+\\d{4})?\\s+(\\S+)\\s+(\\S.*)$");
    private static final Pattern FACILITY_LEVEL = Pattern.compile(
            "^(?:kern|user|mail|daemon|auth|syslog|lpr|news|uucp|cron|authpriv|ftp|local[0-7])\\."
                    + "(emerg|alert|crit|err|error|warning|warn|notice|info|debug)$");
    private static final Pattern ELG = Pattern.compile(
            "^\\[([^\\]]*)\\]@[^\\s\\[\\]]*\\[\\d{1,2} [A-Za-z]{3} \\d{2}:\\d{2}:\\d{2}\\]\\s?(.*)$");
    private static final Pattern FWK = Pattern.compile(
            "^\\[\\d{1,2} [A-Za-z]{3} \\d{2}:\\d{2}:\\d{2}\\]\\[[^\\]]*\\];\\[[^\\]]*\\];\\[[^\\]]*\\]\\s*"
                    + "(?:\\[([A-Za-z]+)\\]:?)?\\s*(.*)$");
    private static final Pattern LEADING_LEVEL = Pattern.compile(
            "^\\[?(emerg(?:ency)?|alert|crit(?:ical)?|fatal|severe|err(?:or)?|warn(?:ing)?|notice|info(?:rmational)?|debug)\\]?(?::|\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAN_SEVERITY = Pattern.compile("(?:^|\\s)severity=(\\w+)");
    private static final Pattern PAN_SUBTYPE = Pattern.compile("(?:^|\\s)subtype=(\\S*)");
    private static final Pattern PAN_EVENTID = Pattern.compile("(?:^|\\s)eventid=(\\S*)");
    private static final Pattern PAN_OPAQUE = Pattern.compile("(?:^|\\s)opaque=\"(.*)\"\\s*$");

    private static final Map<String, Severity> LEVEL_WORDS = Map.ofEntries(
            Map.entry("emerg", Severity.CRITICAL), Map.entry("emergency", Severity.CRITICAL),
            Map.entry("alert", Severity.CRITICAL), Map.entry("crit", Severity.CRITICAL),
            Map.entry("critical", Severity.CRITICAL), Map.entry("fatal", Severity.CRITICAL),
            Map.entry("severe", Severity.CRITICAL), Map.entry("err", Severity.ERROR), Map.entry("error", Severity.ERROR),
            Map.entry("warn", Severity.WARNING), Map.entry("warning", Severity.WARNING),
            Map.entry("notice", Severity.INFO), Map.entry("info", Severity.INFO), Map.entry("informational", Severity.INFO),
            Map.entry("debug", Severity.INFO));
    private static final Map<String, Severity> PAN_LEVELS = Map.of(
            "critical", Severity.CRITICAL, "high", Severity.ERROR, "medium", Severity.WARNING,
            "low", Severity.INFO, "informational", Severity.INFO);

    private CensusLineShapes() {
    }

    /** The message and severity of one masked line of {@code sourceKey}, or {@code null} when the line has no header. */
    public static Parsed parse(String sourceKey, String line) {
        if (line == null) return null;
        switch (sourceKey) {
            case "cp_messages":
                return parseMessages(line);
            case "cp_fwd_elg":
            case "cp_cpd_elg":
            case "cp_cpwd_elg":
            case "cp_mq_mng_elg":
                return parseElg(line);
            case "cp_fwk_elg":
                return parseFwk(line);
            case "pan_system":
                return parsePanSystem(line);
            default:
                // Sources without a measured header (spike, config): the whole line is the message, no level.
                return line.isBlank() ? null : new Parsed(line, Severity.UNKNOWN);
        }
    }

    private static Parsed parseMessages(String line) {
        Matcher m = MESSAGES.matcher(line);
        if (!m.matches()) return null;
        Severity severity = Severity.UNKNOWN;
        if (m.group(1) != null) {
            int priority = Integer.parseInt(m.group(1));
            if (priority > 191) return null; // not a syslog priority
            severity = fromSyslogSeverity(priority % 8);
        }
        String rest = m.group(3);
        int space = rest.indexOf(' ');
        String first = space < 0 ? rest : rest.substring(0, space);
        Matcher fl = FACILITY_LEVEL.matcher(first);
        if (fl.matches()) {
            severity = LEVEL_WORDS.getOrDefault(fl.group(1), Severity.UNKNOWN);
            rest = space < 0 ? "" : rest.substring(space + 1).strip();
            if (rest.isEmpty()) return null;
        }
        return new Parsed(rest, severity);
    }

    private static Severity fromSyslogSeverity(int s) {
        return switch (s) {
            case 0, 1, 2 -> Severity.CRITICAL;
            case 3 -> Severity.ERROR;
            case 4 -> Severity.WARNING;
            default -> Severity.INFO;
        };
    }

    private static Parsed parseElg(String line) {
        Matcher m = ELG.matcher(line);
        if (!m.matches()) return null;
        String header = m.group(1).strip();
        int space = header.indexOf(' ');
        String proc = space < 0 ? header : header.substring(0, space);
        String text = m.group(2).strip();
        if (text.isEmpty()) return null;
        return new Parsed(proc + ": " + text, leadingLevel(text));
    }

    private static Parsed parseFwk(String line) {
        Matcher m = FWK.matcher(line);
        if (!m.matches()) return null;
        String text = m.group(2).strip();
        if (text.isEmpty()) return null;
        Severity severity = m.group(1) == null ? leadingLevel(text)
                : LEVEL_WORDS.getOrDefault(m.group(1).toLowerCase(Locale.ROOT), Severity.UNKNOWN);
        return new Parsed("fwk: " + text, severity);
    }

    private static Severity leadingLevel(String text) {
        Matcher m = LEADING_LEVEL.matcher(text);
        return m.find() ? LEVEL_WORDS.getOrDefault(m.group(1).toLowerCase(Locale.ROOT), Severity.UNKNOWN) : Severity.UNKNOWN;
    }

    private static Parsed parsePanSystem(String line) {
        Matcher sev = PAN_SEVERITY.matcher(line);
        Matcher sub = PAN_SUBTYPE.matcher(line);
        Matcher eid = PAN_EVENTID.matcher(line);
        if (!sev.find() || !sub.find() || !eid.find()) return null;
        Matcher opaque = PAN_OPAQUE.matcher(line);
        String text = opaque.find() ? opaque.group(1).strip() : "";
        String head = sub.group(1) + "/" + eid.group(1) + ":";
        Severity severity = PAN_LEVELS.getOrDefault(sev.group(1).toLowerCase(Locale.ROOT), Severity.UNKNOWN);
        return new Parsed(text.isEmpty() ? head : head + " " + text, severity);
    }

    // ------------------------------------------------------------------ markers

    private static final Pattern EDAC_UE = Pattern.compile("(?i)\\bEDAC\\b.*\\b(?:UE|uncorrected|uncorrectable)\\b");
    private static final Pattern EDAC_CE = Pattern.compile("(?i)\\bEDAC\\b.*\\b(?:CE|corrected)\\b");
    private static final Pattern MCE = Pattern.compile("(?i)\\bmce\\b.*\\bhardware error\\b|\\[Hardware Error\\]");
    private static final Pattern PANIC = Pattern.compile("(?i)\\bkernel panic\\b|\\bOops:\\s");
    private static final Pattern IO_ERROR = Pattern.compile("(?i)\\bI/O error\\b");
    private static final Pattern READONLY = Pattern.compile("(?i)\\bremount\\w*\\b.*\\bread-only\\b");
    private static final Pattern OOM = Pattern.compile("(?i)out of memory: kill(?:ed)? process|\\boom-killer\\b|\\binvoked oom");
    private static final Pattern BOOT = Pattern.compile("\\bLinux version \\d|\\bKernel command line:|\\bBOOT_IMAGE=");

    /** The hardware/kernel marker of a masked line, or {@code null}. Checked in a fixed order; the first match wins. */
    public static Marker marker(String maskedLine) {
        if (maskedLine == null) return null;
        if (EDAC_UE.matcher(maskedLine).find()) return Marker.EDAC_UE;
        if (EDAC_CE.matcher(maskedLine).find()) return Marker.EDAC_CE;
        if (MCE.matcher(maskedLine).find()) return Marker.MCE_HARDWARE_ERROR;
        if (PANIC.matcher(maskedLine).find()) return Marker.KERNEL_PANIC;
        if (IO_ERROR.matcher(maskedLine).find()) return Marker.IO_ERROR;
        if (READONLY.matcher(maskedLine).find()) return Marker.READONLY_REMOUNT;
        if (OOM.matcher(maskedLine).find()) return Marker.OOM_KILLER;
        if (BOOT.matcher(maskedLine).find()) return Marker.BOOT;
        return null;
    }

    // ------------------------------------------------------------------ normalisation

    /**
     * Masked-token shapes the aiview masking writes: keyed host labels, keyed identity tokens, device and cluster
     * pseudonyms (the same shapes as the 2026-11-10 masking-risk measurement).
     */
    static final Pattern MASKED_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])(?:(?:h-[0-9a-f]{10}|(?:USR|SN|ID|POL|RUL|OBJ|ZON|VSN|DMN)-[0-9A-F]{10})(?:[@.](?:h-[0-9a-f]{10}|[A-Za-z]{2,24}))*"
                    + "|[A-Z]{2,4}(?:-[A-Z]+)+-M?\\d{1,3}(?:-[A-Z0-9]+)*)(?![A-Za-z0-9])");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern HEX_LITERAL = Pattern.compile("\\b0[xX][0-9a-fA-F]+\\b");
    private static final Pattern LONG_HEX = Pattern.compile("\\b(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{8,}\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern WILD_RUN = Pattern.compile("(?:<\\*>){2,}");

    /** The message as the miner sees it: identifiers, addresses, masked tokens, hex and numbers collapsed to the wildcard. */
    public static String normalize(String message) {
        String s = message;
        s = CensusMaskGuard.EMAIL.matcher(s).replaceAll(TemplateMiner.WILDCARD);
        s = MASKED_TOKEN.matcher(s).replaceAll(TemplateMiner.WILDCARD);
        s = CensusMaskGuard.IPV4_CANDIDATE.matcher(s).replaceAll(r -> CensusMaskGuard.isIpv4(r.group(1))
                ? Matcher.quoteReplacement(TemplateMiner.WILDCARD) : Matcher.quoteReplacement(r.group()));
        s = CensusMaskGuard.MAC.matcher(s).replaceAll(TemplateMiner.WILDCARD);
        s = CensusMaskGuard.IPV6_TOKEN.matcher(s).replaceAll(TemplateMiner.WILDCARD);
        s = CensusMaskGuard.FQDN.matcher(s).replaceAll(TemplateMiner.WILDCARD);
        s = UUID.matcher(s).replaceAll(TemplateMiner.WILDCARD);
        s = HEX_LITERAL.matcher(s).replaceAll(TemplateMiner.WILDCARD);
        s = LONG_HEX.matcher(s).replaceAll(TemplateMiner.WILDCARD);
        s = DIGITS.matcher(s).replaceAll(Matcher.quoteReplacement(TemplateMiner.WILDCARD));
        return WILD_RUN.matcher(s).replaceAll(TemplateMiner.WILDCARD);
    }
}
