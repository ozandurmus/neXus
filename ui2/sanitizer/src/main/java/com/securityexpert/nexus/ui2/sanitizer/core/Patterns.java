package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Set;
import java.util.regex.Pattern;

/** The detector patterns shared by sanitize and revert. Boundaries are explicit look-arounds, never {@code \b}. */
final class Patterns {

    private Patterns() {
    }

    static final String LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\p{M}-]{0,61}[\\p{L}\\p{N}\\p{M}])?";
    private static final String NAME_CHAR = "\\p{L}\\p{N}\\p{M}";

    /** Dotted-quad candidates; {@link Addresses#parseV4} decides. Not inside a longer dotted number (OID, version). */
    static final Pattern IPV4 = Pattern.compile(
            "(?<![" + NAME_CHAR + "_.])(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(?:/(\\d{1,2})(?!\\d))?"
                    + "(?![" + NAME_CHAR + "_]|\\.\\d)");

    /** Runs of IPv6 characters; validated (and trimmed) by {@link Addresses#parseV6}. */
    static final Pattern IPV6_RUN = Pattern.compile("(?<![" + NAME_CHAR + "_:.])[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
    static final Pattern ZONE = Pattern.compile("%(?:25)?[A-Za-z0-9._~-]{1,32}");
    static final Pattern PREFIX = Pattern.compile("/(\\d{1,3})(?!\\d)");

    static final Pattern MAC = Pattern.compile(
            "(?<![" + NAME_CHAR + "_:.-])(?:[0-9A-Fa-f]{2}([:-])[0-9A-Fa-f]{2}(?:\\1[0-9A-Fa-f]{2}){4}"
                    + "|[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4})"
                    + "(?![" + NAME_CHAR + "_]|[:.-][0-9A-Fa-f])");

    static final Pattern FQDN = Pattern.compile(
            "(?<![" + NAME_CHAR + "_.@-])(?:" + LABEL + "\\.)+" + LABEL + "(?![" + NAME_CHAR + "_-]|\\.[" + NAME_CHAR + "])");

    static final Pattern EMAIL = Pattern.compile(
            "(?<![" + NAME_CHAR + "._%+-])([" + NAME_CHAR + "._%+-]{1,64})@((?:" + LABEL + "\\.)+" + LABEL + ")"
                    + "(?![" + NAME_CHAR + "_-]|\\.[" + NAME_CHAR + "])");

    /** {@code user@host} with a single-label host, as shell prompts and SSH logs write it. */
    static final Pattern USER_AT_HOST = Pattern.compile(
            "(?<![^\\s\"'(<\\[=,;])([\\p{L}\\p{N}._-]{1,64})@([A-Za-z][A-Za-z0-9-]{0,62})(?=[:\\s\"'>)\\],;]|$)");

    static final Pattern URL = Pattern.compile("(?i)(?<![A-Za-z0-9+.-])[a-z][a-z0-9+.-]{1,15}://");

    /** {@code hostname fw01}, {@code devname="fw01"}, {@code host=fw01}: a single label after a host keyword. */
    static final Pattern CONTEXT_HOST = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:(?:hostname|host_name|sysname|devname|device_name|devicename|computername|computer_name)"
                    + "\\s*[=:]?\\s*|(?:host|server|node|dvc|shost|dhost|src_host|dst_host|srchost|dsthost)\\s*[=:]\\s*)"
                    + "[\"']?([A-Za-z][A-Za-z0-9_-]{0,62})(?![A-Za-z0-9_.-]*[A-Za-z0-9_])");

    /** RFC 3164 ({@code Oct 16 12:00:00 fw01 ...}) and RFC 5424 ({@code <34>1 2026-... fw01 ...}) header hosts. */
    static final Pattern SYSLOG_3164 = Pattern.compile(
            "^(?:<\\d{1,3}>)?(?:[A-Z][a-z]{2} [ 0-3]\\d \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?|\\d{4}-\\d{2}-\\d{2}T\\S+) "
                    + "([A-Za-z][A-Za-z0-9_-]{0,62})(?= )");
    static final Pattern SYSLOG_5424 = Pattern.compile("^<\\d{1,3}>\\d{1,2} \\S+ ([A-Za-z][A-Za-z0-9_-]{0,62})(?= )");

    /** Word-like tokens looked up against the known device names. */
    static final Pattern WORD = Pattern.compile("(?<![" + NAME_CHAR + "_.-])[" + NAME_CHAR + "_][" + NAME_CHAR + "_.-]*");

    /** Text right before a dotted quad that marks it as a version, not an address. */
    static final Pattern VERSION_BEFORE = Pattern.compile("(?i)(?:^|[^A-Za-z])(?:v|ver|version|rev|release|build|firmware|fw)[\\s:=]*$");

    /** Revert: the name-token and device-pseudonym shapes. */
    static final Pattern LABEL_TOKEN = Pattern.compile("(?<![A-Za-z0-9])(?:h-[0-9a-f]{10}|H-[0-9A-F]{10})(?![A-Za-z0-9])");
    static final Pattern DEVICE_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:FW|CLS|VS|DOM)-[A-Z]+-\\d{2}(?:-(?:M\\d{1,2}|\\d{2}))?(?![A-Za-z0-9_-])");

    /** Two-label names whose "TLD" is far more often a file extension ({@code setup.py}, {@code notes.md}). */
    static final Set<String> FILE_LIKE_TLDS = Set.of("zip", "py", "sh", "md", "mov", "pl", "rs", "ps", "cc", "so", "cs",
            "ai", "mp", "ms", "gz", "js", "ts", "jar", "bat", "exe", "txt", "log", "cfg", "ini", "csv", "pdf", "png", "jpg");

    /** Bare cell / JSON values under one of these headers or keys are host names even without a keyword. */
    static final Pattern HOST_HEADER = Pattern.compile(
            "(?iu)^\\s*(?:host|hostname|host[ _-]?name|device|device[ _-]?name|devname|node|node[ _-]?name|server"
                    + "|server[ _-]?name|fqdn|dns[ _-]?name|computer|computer[ _-]?name|asset|asset[ _-]?name|sysname"
                    + "|system[ _-]?name|name|firewall|gateway|cihaz|cihaz[ _-]?ad[ıi]|sunucu|sunucu[ _-]?ad[ıi]"
                    + "|makine|makine[ _-]?ad[ıi]|host[ _-]?ad[ıi]|ad)\\s*$");

    /** A bare value treated as a host name in a host column: one label-like word, not a number. */
    static final Pattern BARE_HOST = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}_-]{0,62}");

    static final Set<String> HOST_STOPWORDS = Set.of("unknown", "none", "null", "n/a", "na", "true", "false", "any",
            "all", "yes", "no", "localhost", "default", "-", "", "local");
}
