package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Set;
import java.util.regex.Pattern;

/** The detector patterns shared by sanitize and revert. Boundaries are explicit look-arounds, never {@code \b}. */
final class Patterns {

    private Patterns() {
    }

    static final String LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\p{M}-]{0,61}[\\p{L}\\p{N}\\p{M}])?";
    private static final String NAME_CHAR = "\\p{L}\\p{N}\\p{M}";

    /**
     * Dotted-quad candidates; {@link Addresses#parseV4} decides. Not inside a longer dotted number (OID, version). An
     * underscore is a delimiter here ({@code logmask-v5}): object-like tokens such as {@code net_192.0.2.7} carry the
     * address glued to {@code _}.
     */
    static final Pattern IPV4 = Pattern.compile(
            "(?<![" + NAME_CHAR + ".])(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(?:/(\\d{1,2})(?!\\d))?"
                    + "(?![" + NAME_CHAR + "]|\\.\\d)");

    /** Runs of IPv6 characters; validated (and trimmed) by {@link Addresses#parseV6}. */
    static final Pattern IPV6_RUN = Pattern.compile("(?<![" + NAME_CHAR + "_:.])[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
    static final Pattern ZONE = Pattern.compile("%(?:25)?[A-Za-z0-9._~-]{1,32}");
    static final Pattern PREFIX = Pattern.compile("/(\\d{1,3})(?!\\d)");

    static final Pattern MAC = Pattern.compile(
            "(?<![" + NAME_CHAR + "_:.-])(?:[0-9A-Fa-f]{1,2}([:-])[0-9A-Fa-f]{1,2}(?:\\1[0-9A-Fa-f]{1,2}){4}"
                    + "|[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4})"
                    + "(?![" + NAME_CHAR + "_]|[:.-][0-9A-Fa-f:])");

    static final Pattern FQDN = Pattern.compile(
            "(?<![" + NAME_CHAR + "_.@-])(?:" + LABEL + "\\.)+" + LABEL + "(?![" + NAME_CHAR + "_-]|\\.[" + NAME_CHAR + "])");

    static final Pattern EMAIL = Pattern.compile(
            "(?<![" + NAME_CHAR + "._%+-])([" + NAME_CHAR + "._%+-]{1,64})@((?:" + LABEL + "\\.)+" + LABEL + ")"
                    + "(?![" + NAME_CHAR + "_-]|\\.[" + NAME_CHAR + "])");

    private static final String SINGLE_HOST_NO_UNDERSCORE = "(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9][A-Za-z0-9-]{0,62}";
    private static final String SINGLE_HOST = "(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9][A-Za-z0-9_-]{0,62}";

    /** {@code user@host} with a single-label host, as shell prompts, SSH logs and single-label realms write it. */
    static final Pattern USER_AT_HOST = Pattern.compile(
            "(?<![^\\s\"'(<\\[=,:;])((?:[\\p{L}\\p{N}_][\\p{L}\\p{N}._-]{0,31}/)?[\\p{L}\\p{N}._-]{1,64})@("
                    + SINGLE_HOST_NO_UNDERSCORE + ")(?=[:\\s\"'>)\\],;]|$)");

    /** {@code DOMAIN&#92;user} (or JSON-escaped {@code DOMAIN\\user}) principal. */
    static final Pattern DOMAIN_USER = Pattern.compile(
            "(?<![" + NAME_CHAR + "_./:\\\\-])([A-Za-z][A-Za-z0-9._-]{1,31})(\\\\{1,2})([A-Za-z0-9_][A-Za-z0-9._$-]{1,63})(?!["
                    + NAME_CHAR + "_./\\\\-])");

    /** Account name inside a home directory path: group 1 is the name. */
    static final Pattern HOME_PATH = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])(?:/home/|/Users/|[A-Z]:\\\\{1,2}Users\\\\{1,2}|/export/home/)"
            + "([\\p{L}\\p{N}_][\\p{L}\\p{N}._-]{0,63})(?=[/\\\\\\s\"'),;:]|$)");

    private static final String DN_ATTR = "(?:cn|uid|ou|o|dc|l|st|c|mail|emailaddress|street|title|sn|givenname|serialnumber|domaincomponent)";

    /** LDAP / X.509 Distinguished Names starting with {@code CN=} or {@code UID=} (or slash-separated {@code /CN=...}). */
    static final Pattern CN_DN = Pattern.compile(
            "(?i)(?<![" + NAME_CHAR + "_./-])(?:cn|uid)\\s*=\\s*(?:\"[^\"]+\"|[^,;\"'\\s)\\]]+(?:\\s+(?=[^,;\"')\\]]*[,;]\\s*"
                    + DN_ATTR + "\\s*=)[^,;\"'\\s)\\]]+)*)"
                    + "(?:\\s*[,;]\\s*" + DN_ATTR + "\\s*=\\s*(?:\"[^\"]+\"|[^,;\"'\\s)\\]]+(?:\\s+(?=[^,;\"')\\]]*[,;]\\s*"
                    + DN_ATTR + "\\s*=)[^,;\"'\\s)\\]]+)*))*");
    static final Pattern SLASH_DN = Pattern.compile(
            "(?i)(?<![" + NAME_CHAR + "_.-])(?:/" + DN_ATTR + "=(?:\"[^\"]+\"|[^/\"'\\s)\\]]+)){2,}");
    static final Pattern DN_RDN = Pattern.compile(
            "(?i)\\b(" + DN_ATTR + "\\s*=\\s*)(\"[^\"]+\"|[^,;/\"')\\]]+)");

    static final Pattern URL = Pattern.compile("(?i)(?<![A-Za-z0-9+.-])[a-z][a-z0-9+.-]{1,15}://");

    /** {@code hostname fw01}, {@code devname="fw01"}, {@code host=fw01}: a single label after a host keyword. */
    static final Pattern CONTEXT_HOST = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:(?:hostname|host_name|sysname|devname|device_name|devicename|computername|computer_name)"
                    + "\\s*[=:]?\\s*|(?:host|server|node|dvc|shost|dhost|src_host|dst_host|srchost|dsthost)\\s*[=:]\\s*)"
                    + "[\"']?(" + SINGLE_HOST + ")(?![A-Za-z0-9_.-]*[A-Za-z0-9_])");

    /** Single-word host in parenthesized, state, or prepositional context without a host keyword. */
    static final Pattern NON_KEYWORD_CONTEXT_HOST = Pattern.compile(
            "(?i)(?<![" + NAME_CHAR + "_.-])(?:"
                    + "(?:name|found)\\s+\\(\\s*(" + SINGLE_HOST + ")"
                    + "|(?:sync(?:ed|ing)?(?:\\s+lost)?\\s+with|(?:peering|session|connected)\\s+with|(?:routed|reachable|connected)\\s+via|(?:installed|policy|running)\\s+on)\\s+[\"']?("
                    + SINGLE_HOST + ")(?![A-Za-z0-9_.-])"
                    + "|(" + SINGLE_HOST + ")(?=\\s+(?:(?:is|was|went|became)\\s+(?:now\\s+|still\\s+)?(?:up|down|active|standby|online|offline|reachable|unreachable)\\b|failed\\s+over\\b|timed\\s+out\\b))"
                    + ")");

    /** Ordinary sizes, units, and ordinals that are never hostnames. */
    static final Pattern NON_HOST_TOKEN = Pattern.compile(
            "(?i)(?:\\d+(?:\\.\\d+)?(?:[kmgtpe]i?b|[kmgtpe]?b(?:ps|ytes?|its?)?|[kmgt]?pps|[num]?s|sec(?:s|onds?)?|min(?:s|utes?)?|hr?s?|hours?|d|days?)\\b"
                    + "|\\d+(?:st|nd|rd|th)\\b|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth)");

    /** RFC 3164 ({@code Oct 16 12:00:00 fw01 ...}, or with the year Gaia prints: {@code Oct 16 12:00:00 2026 fw01 ...}) and RFC 5424 ({@code <34>1 2026-... fw01 ...}) header hosts. */
    static final Pattern SYSLOG_3164 = Pattern.compile(
            "^(?:<\\d{1,3}>)?(?:[A-Z][a-z]{2} [ 0-3]\\d \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?: \\d{4})?|\\d{4}-\\d{2}-\\d{2}T\\S+) "
                    + "(" + SINGLE_HOST + ")(?= )");
    static final Pattern SYSLOG_5424 = Pattern.compile("^<\\d{1,3}>\\d{1,2} \\S+ (" + SINGLE_HOST + ")(?= )");

    /** Word-like tokens looked up against the known device names. */
    static final Pattern WORD = Pattern.compile("(?<![" + NAME_CHAR + "_.-])[" + NAME_CHAR + "_][" + NAME_CHAR + "_.-]*");

    /** Text right before a dotted quad that marks it as a version, not an address. */
    static final Pattern VERSION_BEFORE = Pattern.compile("(?i)(?:^|[^A-Za-z])(?:v|ver|version|rev|release|build|firmware|fw)[\\s:=]*$");

    /** Revert: the name-token and device-pseudonym shapes. */
    static final Pattern LABEL_TOKEN = Pattern.compile("(?<![A-Za-z0-9])(?:h-[0-9a-f]{10}|H-[0-9A-F]{10})(?![A-Za-z0-9])");
    static final Pattern DEVICE_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:FW|CLS|VS|DOM)-[A-Z]+-\\d{2}(?:-(?:M\\d{1,2}|\\d{2}))?(?![A-Za-z0-9_-])");

    /** Two-label names whose "TLD" is far more often a file extension ({@code setup.py}, {@code notes.md}). */
    static final Set<String> FILE_LIKE_TLDS = Set.of("zip", "py", "sh", "md", "mov", "so", "cs",
            "gz", "js", "ts", "jar", "bat", "exe", "txt", "log", "cfg", "ini", "csv", "pdf", "png", "jpg");

    /** Bare cell / JSON values under one of these headers or keys are host names even without a keyword. */
    static final Pattern HOST_HEADER = Pattern.compile(
            "(?iu)^\\s*(?:(?:src|dst|source|dest|destination|peer|target|client|remote|local|mgmt|management|member|device|node|host)[ _-]?)?"
                    + "(?:host|hostname|host[ _-]?name|device|device[ _-]?name|devname|node|node[ _-]?name|server"
                    + "|server[ _-]?name|fqdn|dns[ _-]?name|computer|computer[ _-]?name|asset|asset[ _-]?name|sysname"
                    + "|system[ _-]?name|cluster|cluster[ _-]?name|name|firewall|gateway|cihaz|cihaz[ _-]?ad[ıi]"
                    + "|sunucu|sunucu[ _-]?ad[ıi]|makine|makine[ _-]?ad[ıi]|host[ _-]?ad[ıi]|ad)\\s*$");

    /** A bare value treated as a host name in a host column: one label-like word, not a number. */
    static final Pattern BARE_HOST = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}_-]{0,62}");

    static final Set<String> HOST_STOPWORDS = Set.of(
            "unknown", "none", "null", "n/a", "na", "true", "false", "any", "all", "yes", "no", "localhost", "default", "-", "", "local",
            "host", "hosts", "peer", "peers", "node", "nodes", "member", "members", "device", "devices", "gateway", "gateways",
            "server", "servers", "client", "clients", "link", "links", "tunnel", "tunnels", "service", "services", "session", "sessions",
            "connection", "connections", "interface", "interfaces", "port", "ports", "cluster", "clusters", "network", "networks",
            "system", "daemon", "process", "route", "routes", "policy", "policies", "rule", "rules", "channel", "path", "state", "status",
            "line", "check", "attempt", "retry", "request", "operation", "task", "job", "ssh", "ssh2", "http", "https", "tls", "tcp",
            "udp", "icmp", "ipsec", "ike", "bgp", "ospf", "bfd", "lacp", "snmp", "ntp", "dns", "ldap", "radius", "tacacs", "kerberos",
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december",
            "mon", "tue", "wed", "thu", "fri", "sat", "sun", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "1st", "2nd", "3rd", "4th",
            "windows", "system32", "users", "program", "temp", "tmp", "var", "etc", "opt", "usr", "bin", "lib");
}
