package com.securityexpert.nexus.ui2.service.systemlog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.IdentityTokens;
import com.securityexpert.nexus.ui2.sanitizer.core.NameMapper;
import com.securityexpert.nexus.ui2.sanitizer.core.PublicSuffixes;

/**
 * The heuristic free-text pass of the masked system-log view (PO decision 2026-10-19, rule set {@code logmask-v3};
 * the host-context rules 2026-10-21, {@code logmask-v4}):
 * the last pass over a line, after the serial, JSON identity-field, text and estate-dictionary passes. It masks
 * identity-shaped tokens in free text that are neither a known estate identity nor behind a keyed field -- the Palo
 * Alto {@code opaque} message, Check Point clish, cpd and elg text. Over-masking is accepted, under-masking is not, so
 * a token is masked unless it is on the short allowlist below. Masked view only: the unmasked view never runs it.
 *
 * <p><b>Rules</b> (same token families as the keyed rules, so one value reads the same everywhere):</p>
 * <ol>
 *   <li><b>principal mentions</b> become the user token {@code USR-} + 10 hex ({@link IdentityTokens#user}, as the
 *       JSON {@code admin}/{@code user} keys): {@code user=V}, {@code username: V}, {@code admin=V}, {@code login=V};
 *       {@code User 'V'}; sshd's {@code password|publickey for [invalid user] V from}; {@code by|for|from [invalid]
 *       user|admin V}; {@code user|admin V}; {@code by V}. In the three bare forms a small set of ordinary words after
 *       the trigger ({@link #STOPWORDS}: {@code admin login ok}, {@code user is locked}, daemon names as in
 *       {@code restarted by cpwd}) is not a name;</li>
 *   <li><b>elg header host</b>: {@code [proc pid tid]@HOST[DD Mon HH:MM:SS]} (the cpd/cpwd/fwd elg header, findings
 *       record 2026-10-17 section 3.3), whose host the text rules miss, becomes the {@code h-} host label token; so
 *       does the host of the sar header {@code Linux <kernel> (HOST)} (section 4.1);</li>
 *   <li><b>host context</b> ({@code logmask-v4}): the single word right after a host keyword -- {@code host},
 *       {@code hostname}, {@code device}, {@code gateway}, {@code peer}, {@code node}, {@code member} (also
 *       {@code cluster member}, a {@code -name}/{@code _name} spelling, and {@code =} or {@code :} between) -- or
 *       after {@code from} / {@code to} becomes the {@code h-} host label token, even a plain word with no digit,
 *       hyphen or dot ({@code peer berlinfw}, {@code sync from berlinfw}). A word that continues as a dotted name
 *       ({@code to fw.corp.lan}, {@code to R81.20}) is left to the FQDN and version rules; a serial-shaped word to the
 *       serial rule. A name seen after a host keyword (not after {@code from}/{@code to}) is remembered for the rest
 *       of the session, and its bare mentions in later text are masked too. The words that follow these keywords in
 *       ordinary log text are kept ({@link #HOST_CONTEXT_WORDS}, {@link #STOPWORDS}: {@code host key},
 *       {@code peer is down}, {@code from the}, {@code failed to connect}, {@code changed from active to standby},
 *       daemon and product names such as {@code from cpwd}, {@code to Panorama}); so are interface names
 *       ({@code device eth1}), protocol words ({@code to https}), month and day names, and numbers (a name starts with
 *       a letter);</li>
 *   <li><b>FQDN-shaped</b> tokens -- two or more dot-separated labels of which at least two carry a letter and the
 *       last carries a letter and is at least two characters long -- get the {@code h-} label token for every label
 *       except the public suffix (the text rules only mask names under a public suffix, so {@code fw01.corp} and
 *       {@code gw.site.lan} reached the page);</li>
 *   <li><b>hostname-shaped</b> tokens -- letters, digits and hyphens with at least one digit, one hyphen and two
 *       letters, or ending in a cluster-member suffix the pseudonymizer recognises ({@code -aa}, {@code -bb});
 *       or three to six underscore-joined parts ending in a member ordinal ({@code _01}, {@code _m1},
 *       {@code _member2}) -- become the {@code h-} host label token of the whole token, as a host in a syslog header;</li>
 *   <li><b>serial-like</b> tokens -- a run of 10 or more digits, or a letters-and-digits token of 8 or more characters
 *       with at least 6 digits -- become {@code SN-} + 10 hex ({@link IdentityTokens#serial}), the inventory's serial
 *       token. A digit run right after a timestamp key ({@code time=}, {@code "receive_time":}, {@code epoch:}) or a
 *       sequence key ({@code seqno}, {@code seq}, {@code sequence number}) is kept.</li>
 * </ol>
 *
 * <p><b>Allowlist</b> (kept readable; every entry is a non-identity shape the measured log shapes need, findings record
 * 2026-10-17 sections 3.3 and 5, and the existing masking tests):</p>
 * <ul>
 *   <li>file names: trailing labels in {@link #EXTENSIONS} ({@code fwd.elg}, {@code messages.1} is a rotation, not a
 *       name); the stem before them is still checked, so {@code fw01.corp.log} masks {@code fw01.corp};</li>
 *   <li>versions: {@code R81.20}, {@code 10.2.9-h1}, {@code v1.2.3.4} ({@link #VERSION});</li>
 *   <li>protocol and cipher names: {@code AES-256-CBC}, {@code sha2-256}, {@code phase-1}, {@code UTF-8}
 *       ({@link #PROTOCOL_PART}); interface names {@code eth1-01}, {@code bond0.100} (the {@code DiagnosticText}
 *       interface shape);</li>
 *   <li>vendor enum fields: the values of the Palo Alto JSON keys whose values the record lists as enum classes, times
 *       or counters ({@link #ENUM_KEYS}: {@code eventid}, {@code subtype}, {@code seqno}, {@code receive_time} ...) are
 *       not checked by the hostname and serial-like rules ({@code ike-nego-p1-start} stays an event id);</li>
 *   <li><b>hex-encoded addresses</b> ({@code logmask-v6}, 2026-11-11): {@code 0x} plus exactly eight hex digits can be an
 *       IPv4 address, so one right after an address keyword ({@code addr}, {@code src_ip}, {@code peer=}, {@code gateway
 *       :} ...) becomes the identity token {@code ID-} + 10 hex (same value, same token, in either letter case). The
 *       same literal elsewhere stays: kernel masks, pointers and error codes are not identities;</li>
 *   <li>dates ({@code 17-Oct-2026}), UUIDs, MAC addresses, other {@code 0x} hex literals, and tokens the earlier passes
 *       wrote ({@code h-}, {@code SN-}, {@code USR-}, {@code ID-}, {@code FW-TANGO-04}).</li>
 * </ul>
 *
 * <p>Not guaranteed: a single plain word with no digit and no hyphen ({@code berlinfw}) that is not a known name,
 * not right after a host keyword or {@code from}/{@code to}, not learned earlier in the session and not after a
 * principal trigger cannot be told from an ordinary word and stays ({@code sync with berlinfw}, {@code berlinfw is
 * down}); so does a host whose name is itself one of the kept words ({@code peer standby}), or is shorter than three
 * characters. Nothing is logged; only counts per category are kept.</p>
 */
final class FreeTextIdentityRules {

    /** Count categories (counts only, never content). */
    static final String PRINCIPAL = "heuristic_principal";
    static final String HOST = "heuristic_hostname";
    static final String FQDN = "heuristic_fqdn";
    static final String SERIAL = "heuristic_serial";
    static final String HOST_CONTEXT = "heuristic_host_context";
    static final String HEX_ADDRESS_COUNT = "heuristic_hex_address";

    /** File-name extensions (and systemd unit kinds) that make a dotted token a file name, not a host name. */
    static final Set<String> EXTENSIONS = Set.of("log", "elg", "txt", "csv", "json", "xml", "conf", "cfg", "ini", "gz",
            "tgz", "tar", "zip", "bz2", "xz", "pcap", "pcapng", "sh", "py", "so", "c", "h", "fws", "db", "dat", "pid",
            "lock", "tmp", "bak", "old", "core", "out", "err", "html", "js", "jar", "crt", "cer", "pem", "service",
            "socket", "timer", "target", "mount");
    /** Palo Alto JSON keys whose values are vendor enums, times or counters (findings record 2026-10-17 section 5). */
    static final Set<String> ENUM_KEYS = Set.of("logid", "actionflags", "configver", "dghierlevel1", "dghierlevel2",
            "dghierlevel3", "dghierlevel4", "dgid", "domain", "eventid", "fmt", "highrestimestamp", "id",
            "isloggingservice", "module", "receivetime", "result", "client", "cmd", "seqno", "severity", "subtype",
            "timegenerated", "tplid", "type", "vsysid");
    /** Ordinary words after a bare principal trigger that are not a name ({@code admin login ok}). */
    static final Set<String> STOPWORDS = Set.of((""
            + "login logged logout logon logoff log logs session sessions mode account accounts password passwords "
            + "authentication auth authenticated access role roles rights privilege privileges permission "
            + "permissions console interface name names profile profiles group groups id ids list table database "
            + "is was has had have does did from to with for of on in at via and or not no the a an this that "
            + "failed succeeded success failure attempt attempts request requests changed change changes commit "
            + "committed configured config configuration ok unknown invalid illegal user users admin admins "
            + "administrator administrators rule rules policy "
            + "policies peer remote local localhost system kernel signal timeout default server client host "
            + "process daemon command cmd settings entered exited created deleted added removed locked unlocked "
            + "expired cache query lookup management manager running executing "
            // daemon names ("restarted by cpwd"): processes, not principals
            + "cpwd cpd fwd fwm cpm fwk confd clish expert watchdog sshd httpd ntpd snmpd syslogd cron crond "
            + "systemd init mgmtsrvr devsrvr useridd sslmgr authd ").strip().split(" "));

    /**
     * Words that follow a host keyword or {@code from}/{@code to} in ordinary log text and are not a host name: kept
     * readable by the host-context rule (in addition to {@link #STOPWORDS}). Over-masking is accepted, so a word not
     * listed here is masked after such a keyword.
     */
    static final Set<String> HOST_CONTEXT_WORDS = Set.copyOf(java.util.Arrays.asList((""
            // articles, pronouns, determiners, prepositions, conjunctions
            + "the a an this that these those it its they them their there here his her him she he we us our you your "
            + "me my any all some each every none other others another both either neither which what who whom whose "
            + "where when why how whether if then than so such same own only also just still yet even ever never nor "
            + "but as by into off onto out over per since through till until up upon about above across after against "
            + "along among around before behind below beneath beside between beyond during except inside near outside "
            + "past toward towards under unlike within without again more less most least many much few several "
            // verbs after "to" (infinitives), and their common forms
            + "be been being am are were do done get got make made take see let keep go come run start stop restart "
            + "reload open close read write load save send receive accept reject drop allow deny block pass forward "
            + "route create delete remove add update fetch find resolve bind listen connect disconnect reconnect "
            + "establish register unregister initialize initialise install uninstall upgrade downgrade apply push pull "
            + "sync synchronize synchronise retrieve verify validate check handle reach contact kill lock unlock enable "
            + "disable modify configure set reset clear use perform complete completed obtain acquire release generate export "
            + "import upload download copy move rename respond reply notify report parse allocate free attach detach "
            + "mount unmount execute launch call invoke compare compute calculate convert encode decode encrypt decrypt "
            + "sign authenticate authorize authorise logon wait retry try fail succeed continue proceed exit quit abort "
            + "finish begin end show print display collect gather monitor track scan probe ping test measure poll "
            + "return switch fall failover transition become remain stay join leave enter send sent received "
            + "connected disconnected sending receiving connecting forwarding forwarded switched moved "
            // states, levels and ordinary nouns
            + "active standby backup primary secondary master slave passive ready down dead lost problem failure "
            + "error errors warning warnings info notice debug critical alert emergency enabled disabled off true false "
            + "null init initializing initial pending stopped started starting stopping idle busy online offline "
            + "available unavailable reachable unreachable timeout key keys file files disk memory queue buffer socket "
            + "sockets port ports servers clients network networks interfaces database db tables lists object objects "
            + "state status level type value address addresses ip ipv4 ipv6 mac route routes routing gateway gateways "
            + "hosts node nodes member members peers cluster clusters device devices vsx vs vsys domain domains zone "
            + "zones service services connection connections tunnel tunnels link links traffic packet packets message "
            + "messages event events response responses data record records entry entries item items update updates "
            + "version versions license licenses certificate certificates cert certs sic ca crl ocsp mgmt panorama "
            + "cloud wildfire globalprotect portal ldap radius tacacs kerberos saml ntp dns dhcp snmp syslog smtp sftp "
            + "ftp scp ssh telnet tcp udp icmp gre internet external internal inside outside loopback self itself "
            + "everyone nobody someone something nothing anything everything admins root new old next previous last "
            + "first second third current standard normal max min maximum minimum total web gui cli api xml json rest "
            + "file-system disk-space cpu ram swap core kernel-space userspace slot slots blade blades chassis module "
            + "modules sensor sensors fan fans power supply temperature "
            // identity-field and attribute words ("device serial=", "peer address:", "peer subject")
            + "serial sn number model id uuid ip vlan family platform hardware software firmware count info "
            + "information details summary statistics stats priority weight mtu speed duplex description label "
            + "subject subjects issuer issuers dn "
            // Check Point and Palo Alto daemon and component names
            + "fwk fwm fwd fwkern cpd cpm cpwd vpnd iked routed pdpd pepd rad sxl cphaprob cpha ted rtmd mpdaemon "
            + "smartcenter smartconsole sms mds cma ikemgr keymgr varrcvr satd rasmgr logrcvr devsrvr mgmtsrvr "
            + "useridd sslmgr authd ntpd "
            // Palo Alto subtype enum words and common non-host log vocabulary
            + "general auth userid vpn lacp iot telemetry potency negotiation split dynamic opaque flap flapping "
            + "channel channels path paths neighbor neighbors neighbour neighbours instance instances controller "
            + "controllers agent agents worker workers engine engines listener listeners collector collectors "
            + "probe probes check checks monitor monitors stage stages phase phases pass passes retry retries "
            + "window windows frame frames stream streams flow flows hop hops chain chains capture captures "
            + "clock clocks verdict verdicts reason reasons headline headlines note notes detail explanation "
            + "explanations loss delay jitter reorder reordering retransmission retransmissions duplicate duplicates "
            + "selected supported observed detected reported accepted rejected dropped mailbox sandbox inbox outbox "
            + "toolbox checkbox virtualbox blackbox whitebox timebox ").strip().split("\\s+")));

    static final Pattern VERSION = Pattern.compile("(?i)(?:r|v|ver|version)?\\d+(?:\\.\\d+)+(?:[-._]?[a-z]{0,4}\\d*)*");
    static final Pattern PROTOCOL_PART = Pattern.compile(
            "(?i)(?:aes|sha|md|des|3des|rsa|dsa|ecdsa|ecdh|dh|hmac|gcm|cbc|ctr|ccm|group|modp|ecp|prf|tls|ssl|ike|ikev"
                    + "|ipsec|esp|utf|iso|phase|x|ipv|icmpv|snmpv|ntpv|http|https|ssh|rfc|base|pkcs|asn|crc)\\d*");
    /** The {@code DiagnosticText} interface shape ({@code eth1-01}, {@code bond0.100}). */
    private static final Pattern INTERFACE = Pattern.compile(
            "(?i)(?:eth|bond|wrp|ens|eno|enp|wlan|lo|port|vlan|vsys|tun|tap|br)[0-9]{1,4}(?:[-.][0-9]{1,4})?");
    private static final Pattern SIZE_OR_UNIT = Pattern.compile(
            "(?i)\\d+(?:\\.\\d+)?(?:[kmgtpe]i?b|[kmgtpe]?b(?:ps|ytes?|its?)?|[kmgt]?pps"
                    + "|[num]?s|sec(?:s|onds?)?|min(?:s|utes?)?|hr?s?|hours?|d|days?|w|wks?|weeks?|mo|months?|yr?s?|years?"
                    + "|[kmg]?hz|dbm?|pct|[kmgtpe]|v|mv|kv|a|ma|w|kw|mw|c|f)");
    private static final Pattern ORDINAL_TOKEN = Pattern.compile(
            "(?i)(?:\\d+(?:st|nd|rd|th)|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth)");
    private static final Pattern NUMBERED_COMMON_NOUN = Pattern.compile(
            "(?i)(?:sheet|chart|table|style|theme|page|line|item|step|part|type|mode|tier|stage|phase|group|class|code"
                    + "|error|warn|test|case|flow|point|cycle|window|bucket|sample|metric|count|total|slot|core|blade"
                    + "|rule|zone|port|vlan|vsys|cpu|fan|psu|disk|raid|loop|user|admin|vmldrawing|drawing)[0-9]+");
    private static final Pattern SINGLE_WORD_ALNUM_HOST = Pattern.compile(
            "(?i)(?:[a-z]{4,}[0-9]{1,4}|(?:fw|gw|sw|rtr|srv|db|lb|dc|ap|ns|mx)[0-9]{2,4})");
    private static final Pattern SINGLE_WORD_AFFIX_HOST = Pattern.compile(
            "(?i)(?:[a-z]{4,}(?:fw|gw|srv|rtr|box)|(?:fw|gw|srv|rtr)[a-z]{4,})");
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern MAC_HYPHEN = Pattern.compile("(?i)[0-9a-f]{2}(?:-[0-9a-f]{2}){5}");
    private static final Pattern MAC_DOTTED = Pattern.compile("(?i)[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}");
    private static final Pattern HEX_LITERAL = Pattern.compile("0[xX][0-9a-fA-F]+");
    /**
     * {@code 0x} plus exactly eight hex digits right after an address keyword (optionally prefixed, {@code src_ip},
     * {@code peer-addr}) and a separator: the hex form of an IPv4 address.
     */
    private static final Pattern HEX_ADDRESS = Pattern.compile("(?i)(?<![A-Za-z0-9])(?:[A-Za-z0-9]+[_-])*"
            + "(?:ip|ipaddr|addr|address|src|dst|source|destination|peer|gateway|gw|remote|local|client|server|host|nexthop)"
            + "[\"']?\\s*(?:[=:]\\s*|\\s+)[\"']?(0x[0-9a-f]{8})(?![A-Za-z0-9])");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?");
    private static final Pattern ORDINAL = Pattern.compile("(?i)0?[1-9]|10|m[1-9]|m10|member[1-9]|member10|aa|bb");
    private static final Set<String> DATE_WORDS = Set.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep",
            "sept", "oct", "nov", "dec", "january", "february", "march", "april", "june", "july", "august", "september",
            "october", "november", "december", "mon", "tue", "wed", "thu", "fri", "sat", "sun", "monday", "tuesday",
            "wednesday", "thursday", "friday", "saturday", "sunday");
    /** A token an earlier pass wrote, inside a longer token ({@code hcp-SN-0A1B2C3D4E}). */
    private static final Pattern WRITTEN_TOKEN = Pattern.compile(
            "(?:SN|USR|ID)-[0-9A-F]{10}|h-[0-9a-f]{10}|H-[0-9A-F]{10}|(?:FW|CLS|VS|DOM)-[A-Z]+-\\d{2}(?:-(?:M\\d{1,2}|\\d{2}))?");
    private static final Pattern PSEUDONYM = Pattern.compile("(?:FW|CLS|VS|DOM)-[A-Z]+-\\d{2}(?:-(?:M\\d{1,2}|\\d{2}))?");

    private static final String B = "(?<![\\p{L}\\p{N}_])";
    private static final String BH = "(?<![\\p{L}\\p{N}_-])";
    private static final String NAME = "([\\p{L}\\p{N}_][\\p{L}\\p{N}._@\\\\-]{0,63})";
    private static final String DN_ATTR = "(?:cn|uid|ou|o|dc|l|st|c|mail|emailaddress|street|title|sn|givenname|serialnumber|domaincomponent)";
    private static final String CN_DN_BODY = "(?:cn|uid)\\s*=\\s*(?:\"[^\"]+\"|[^,;\"'\\s)\\]]+(?:\\s+(?=[^,;\"')\\]]*[,;]\\s*"
            + DN_ATTR + "\\s*=)[^,;\"'\\s)\\]]+)*)"
            + "(?:\\s*[,;]\\s*" + DN_ATTR + "\\s*=\\s*(?:\"[^\"]+\"|[^,;\"'\\s)\\]]+(?:\\s+(?=[^,;\"')\\]]*[,;]\\s*"
            + DN_ATTR + "\\s*=)[^,;\"'\\s)\\]]+)*))*";
    private static final Pattern P_KEYED_CN_DN = Pattern.compile("(?i)" + B
            + "(?:(?:user(?:[-_ ]?name)?|admin(?:istrator)?(?:[-_ ]?name)?|account|principal|login|uname|ruser|luser|acct|suser|duser|usr|dn|subject|issuer)\\s*[=:]\\s*[\"']?)("
            + CN_DN_BODY + ")");
    private static final Pattern P_CN_DN = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_./-])(" + CN_DN_BODY + ")");
    private static final Pattern P_SLASH_DN = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_.-])((?:/" + DN_ATTR + "=(?:\"[^\"]+\"|[^/\"'\\s)\\]]+)){2,})");
    private static final Pattern P_DOMAIN_USER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_./:\\\\-])([A-Za-z][A-Za-z0-9._-]{1,31})(\\\\{1,2})([A-Za-z0-9_][A-Za-z0-9._$-]{1,63})(?![\\p{L}\\p{N}_./\\\\-])");
    private static final Pattern P_USER_AT_REALM = Pattern.compile(
            "(?<![\\p{L}\\p{N}._%+/\\]-])((?:[\\p{L}\\p{N}_][\\p{L}\\p{N}._-]{0,63}/)?[\\p{L}\\p{N}_][\\p{L}\\p{N}._%+-]{0,63})@([A-Za-z0-9][A-Za-z0-9._-]{1,252})(?![A-Za-z0-9._-])");
    private static final Set<String> PRIVATE_REALM_TLDS = Set.of(
            "local", "internal", "lan", "corp", "intranet", "localdomain", "home", "priv", "test", "invalid");
    private static final Pattern P_FIELD = Pattern.compile("(?i)" + B
            + "(?:(?:user(?:[-_ ]?name)?|account|principal)\\s*[=:](?!//)|(?:admin(?:istrator)?(?:[-_ ]?name)?|login|uname|ruser"
            + "|luser|acct|suser|duser|usr)\\s*=)\\s*[\"']?" + NAME);
    private static final Pattern P_QUOTED = Pattern.compile("(?i)" + BH
            + "(?:user(?:name)?|admin(?:istrator)?|account|login)\\s+[\"'‘’`]([^\"'‘’`\\s][^\"'‘’`]{0,63})[\"'‘’`]");
    private static final Pattern P_SSHD = Pattern.compile("(?i)" + B
            + "(?:password|publickey|keyboard-interactive(?:/pam)?|hostbased|gssapi-with-mic|none)\\s+for\\s+(?:invalid\\s+user\\s+)?"
            + NAME + "\\s+from(?![\\p{L}\\p{N}_])");
    private static final Pattern P_ROLE = Pattern.compile("(?i)" + B
            + "(?:by|for|from|of)\\s+(?:(?:invalid|illegal|unknown|local|remote)\\s+)?(?:user|admin|administrator|account)\\s+[\"']?" + NAME);
    private static final Pattern P_BARE = Pattern.compile("(?i)" + BH + "(?:user|admin|administrator)\\s+" + NAME);
    private static final Pattern P_BY = Pattern.compile("(?i)" + BH + "by\\s+" + NAME);
    /** A single host word after a keyword: a letter first; not the start of a dotted name. */
    private static final String HOST_WORD = "([A-Za-z][A-Za-z0-9_-]{0,62})(?![A-Za-z0-9_-]|\\.[A-Za-z0-9])";
    private static final Pattern P_HOST_KEYWORD = Pattern.compile("(?i)" + BH
            + "(?:host(?:[-_ ]?name)?|device(?:[-_ ]?name)?|gateway(?:[-_ ]?name)?|peer(?:[-_ ]?name)?"
            + "|node(?:[-_ ]?name)?|(?:cluster[-_ ])?member(?:[-_ ]?name)?"
            + "|firewall(?:[-_ ]?name)?|router(?:[-_ ]?name)?|switch(?:[-_ ]?name)?"
            + "|appliance(?:[-_ ]?name)?|neighbor(?:[-_ ]?name)?|neighbour(?:[-_ ]?name)?|nexthop)"
            + "(?:\\s*[=:]\\s*|\\s+)[\"'‘’`(\\[]?" + HOST_WORD);
    private static final Pattern P_FROM_TO = Pattern.compile("(?i)" + BH + "(?:from|to)\\s+[\"'‘’`(\\[]?" + HOST_WORD);
    private static final Pattern P_PAREN_HOST = Pattern.compile(
            "(?i)" + BH + "(?:name|found|peer|host|node|member|gateway|device)\\s+\\(\\s*" + HOST_WORD);
    private static final Pattern P_HOST_BEFORE_STATE = Pattern.compile(
            "(?i)" + BH + HOST_WORD
                    + "(?=\\s+(?:(?:is|was|went|became|remained|stays)\\s+(?:now\\s+|still\\s+|currently\\s+)?(?:up|down|active|standby|online|offline|reachable|unreachable|alive|dead|ready|lost|connected|disconnected|primary|secondary|master|backup)\\b"
                    + "|failed\\s+over\\b|timed\\s+out\\b|(?:is\\s+|was\\s+)?not\\s+responding\\b|stopped\\s+responding\\b|restarted\\b|rebooted\\b))");
    private static final Pattern P_CLAUSE_HOST_STATE = Pattern.compile(
            "(?i)(?:^|[:;,\"']\\s*)" + HOST_WORD
                    + "(?=\\s+(?:up|down|active|standby|online|offline|reachable|unreachable)(?=[,;.)\\]\"']|\\s*$))");
    private static final Pattern P_HOST_PREP_OR_VERB = Pattern.compile(
            "(?i)" + BH + "(?:sync(?:ed|ing|hroniz(?:ed|ing))?(?:\\s+lost)?\\s+with"
                    + "|(?:peering|session|connected|connection|handshake|communicating|established)\\s+with"
                    + "|(?:routed|routing|reachable|connected|traffic|forwarded|forwarding|sent)\\s+via"
                    + "|(?:installed|policy|running|listening|configured|active|standby|mounted|deployed)\\s+on"
                    + "|(?:probing|probed|pinging|pinged|contacting|contacted|resolving|resolved|reaching|reached|polling|polled)\\s+)"
                    + "\\s*[\"'‘’`(\\[]?" + HOST_WORD);
    private static final Pattern SAR_HOST = Pattern.compile("^Linux \\S+ \\(([A-Za-z0-9][A-Za-z0-9_-]{0,62})\\)");
    /** At most this many host names are remembered per session. */
    static final int MAX_LEARNED = 10_000;
    private static final Pattern ELG_HOST = Pattern.compile("\\]@([A-Za-z0-9][A-Za-z0-9._-]{0,252})(?=\\[)");
    private static final Pattern ENUM_FIELD = Pattern.compile("\"@?([A-Za-z0-9_-]{2,32})\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SEQ_BEFORE = Pattern.compile("(?i)(?:^|[^A-Za-z0-9])(?:seq(?:no|num|uence)?(?:[ _-]?(?:no|num|number|id))?)"
            + "[\"']?\\s*(?:[=:#]\\s*[\"']?|\\s+)$");
    private static final Pattern TIME_BEFORE = Pattern.compile("(?i)(?:^|[^A-Za-z0-9])(?:[A-Za-z0-9]+[_-])*"
            + "(?:time|timestamp|epoch|date|ts|tstamp|mtime|ctime|atime|since|until|expires?)[\"']?\\s*[=:]\\s*[\"']?$");

    /** One replacement: {@code [start, end)} becomes {@code text}; {@code guard} is the span no later rule may touch. */
    private record Edit(int start, int end, String text, String category, int guardStart, int guardEnd) {
    }

    private final NameMapper names;
    private final IdentityTokens tokens;
    private final PublicSuffixes suffixes = PublicSuffixes.get();
    /** Host names (lower case) seen after a host keyword in this session; their bare mentions follow. */
    private final Set<String> learned = new java.util.HashSet<>();

    FreeTextIdentityRules(NameMapper names, IdentityTokens tokens) {
        this.names = names;
        this.tokens = tokens;
    }

    /** The line with every identity-shaped token masked. */
    String apply(String line, Counts counts) {
        if (line == null || line.length() < 2) return line;
        List<Edit> edits = new ArrayList<>();
        hexAddresses(line, edits);
        principals(line, edits);
        elgHosts(line, edits);
        List<int[]> enumSpans = enumSpans(line);
        hostContexts(line, edits, enumSpans);
        tokens(line, edits, enumSpans);
        if (edits.isEmpty()) return line;
        edits.sort(Comparator.comparingInt(Edit::start));
        StringBuilder out = new StringBuilder(line.length() + 32);
        int copied = 0;
        for (Edit e : edits) {
            out.append(line, copied, e.start()).append(e.text());
            copied = e.end();
            counts.add(e.category());
        }
        return out.append(line, copied, line.length()).toString();
    }

    // ---------------------------------------------------------------- hex-encoded addresses

    private void hexAddresses(String line, List<Edit> edits) {
        if (line.length() < 10 || line.indexOf('x') < 0 && line.indexOf('X') < 0) return;
        Matcher m = HEX_ADDRESS.matcher(line);
        while (m.find()) {
            String literal = "0x" + m.group(1).substring(2).toLowerCase(Locale.ROOT);
            add(edits, new Edit(m.start(1), m.end(1), tokens.payload(IdentityTokens.IDENTITY, literal), HEX_ADDRESS_COUNT,
                    m.start(1), m.end(1)));
        }
    }

    /**
     * Self-identifying principal formats ({@code DOMAIN&#92;user}, {@code user@realm}, {@code CN=} DNs) masked before
     * {@code TextSanitizer} runs so they become canonical {@code USR-} tokens instead of generic email/FQDN labels.
     */
    String preTextPrincipals(String line, Counts counts) {
        if (line == null || line.length() < 3) return line;
        List<Edit> edits = new ArrayList<>();
        selfIdentifyingPrincipals(line, edits);
        if (edits.isEmpty()) return line;
        edits.sort(Comparator.comparingInt(Edit::start));
        StringBuilder out = new StringBuilder(line.length() + 32);
        int copied = 0;
        for (Edit e : edits) {
            out.append(line, copied, e.start()).append(e.text());
            copied = e.end();
            counts.add(e.category());
        }
        return out.append(line, copied, line.length()).toString();
    }

    // ---------------------------------------------------------------- principals

    private void principals(String line, List<Edit> edits) {
        selfIdentifyingPrincipals(line, edits);
        if (!mayHavePrincipal(line)) return;
        principal(line, P_FIELD, false, edits);
        principal(line, P_QUOTED, false, edits);
        principal(line, P_SSHD, false, edits);
        principal(line, P_ROLE, true, edits);
        principal(line, P_BARE, true, edits);
        principal(line, P_BY, true, edits);
    }

    private void selfIdentifyingPrincipals(String line, List<Edit> edits) {
        if (line.indexOf('=') >= 0) {
            Matcher keyedDn = P_KEYED_CN_DN.matcher(line);
            while (keyedDn.find()) {
                String dn = keyedDn.group(1).strip();
                if (!dn.isEmpty() && !written(dn)) {
                    add(edits, new Edit(keyedDn.start(1), keyedDn.end(1), tokens.user(dn), PRINCIPAL, keyedDn.start(), keyedDn.end()));
                }
            }
            Matcher cnDn = P_CN_DN.matcher(line);
            while (cnDn.find()) {
                String dn = cnDn.group(1).strip();
                if (!dn.isEmpty() && !written(dn)) {
                    add(edits, new Edit(cnDn.start(1), cnDn.end(1), tokens.user(dn), PRINCIPAL, cnDn.start(1), cnDn.end(1)));
                }
            }
            if (line.indexOf('/') >= 0) {
                Matcher slashDn = P_SLASH_DN.matcher(line);
                while (slashDn.find()) {
                    String dn = slashDn.group(1).strip();
                    String lower = dn.toLowerCase(Locale.ROOT);
                    if (!dn.isEmpty() && !written(dn) && (lower.contains("/cn=") || lower.contains("/uid="))) {
                        add(edits, new Edit(slashDn.start(1), slashDn.end(1), tokens.user(dn), PRINCIPAL,
                                slashDn.start(1), slashDn.end(1)));
                    }
                }
            }
        }
        if (line.indexOf('\\') >= 0) {
            Matcher domUser = P_DOMAIN_USER.matcher(line);
            while (domUser.find()) {
                String domain = domUser.group(1);
                String sep = domUser.group(2);
                String user = domUser.group(3);
                String domLower = domain.toLowerCase(Locale.ROOT);
                String usrLower = user.toLowerCase(Locale.ROOT);
                if (written(domain) || written(user) || STOPWORDS.contains(domLower) || HOST_CONTEXT_WORDS.contains(domLower)
                        || DATE_WORDS.contains(domLower) || EXTENSIONS.contains(usrLower)
                        || Set.of("windows", "system32", "users", "program", "temp", "tmp", "var", "etc", "opt", "usr", "bin", "lib")
                                .contains(domLower)) {
                    continue;
                }
                if ("\\".equals(sep) && user.matches("(?i)u[0-9a-f]{4}.*")) continue;
                add(edits, new Edit(domUser.start(), domUser.end(), tokens.user(domain + "\\" + user), PRINCIPAL,
                        domUser.start(), domUser.end()));
            }
        }
        if (line.indexOf('@') >= 0) {
            Matcher realmUser = P_USER_AT_REALM.matcher(line);
            while (realmUser.find()) {
                String user = realmUser.group(1);
                int start = realmUser.start();
                int end = realmUser.end();
                while (end > realmUser.start(2) && ".-".indexOf(line.charAt(end - 1)) >= 0) end--;
                String realm = line.substring(realmUser.start(2), end);
                if (realm.isEmpty() || written(user) || written(realm)
                        || EstateDictionary.ADDRESS_LITERAL.matcher(realm).matches()) {
                    continue;
                }
                String before = line.substring(Math.max(0, start - 24), start).toLowerCase(Locale.ROOT);
                if (before.endsWith("://") || before.contains("mail from") || before.contains("rcpt to")
                        || before.endsWith("mailto:") || before.endsWith("email ") || before.endsWith("e-mail ")) {
                    continue;
                }
                if (line.regionMatches(end, ":~$", 0, 3) || line.regionMatches(end, ":#", 0, 2)) continue;
                if (isPrincipalRealm(line, user, realm)) {
                    String full = user + "@" + realm;
                    add(edits, new Edit(start, end, tokens.user(full), PRINCIPAL, start, end));
                }
            }
        }
    }

    private boolean isPrincipalRealm(String line, String user, String realm) {
        if (user.indexOf('/') >= 0 || realm.indexOf('.') < 0) return true;
        for (int i = 0; i < realm.length(); i++) {
            char c = realm.charAt(i);
            if (c >= 'A' && c <= 'Z') return true;
        }
        String[] labels = realm.split("\\.", -1);
        String tld = labels[labels.length - 1].toLowerCase(Locale.ROOT);
        if (PRIVATE_REALM_TLDS.contains(tld) || suffixes.suffixLabels(labels) == 0) return true;
        if (mayHavePrincipal(line)) return true;
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("kerberos") || lower.contains("krb") || lower.contains("ntlm") || lower.contains("ldap")
                || lower.contains("upn") || lower.contains("spn") || lower.contains("tgt") || lower.contains("ticket")
                || lower.contains("realm") || lower.contains("sso") || lower.contains("saml") || lower.contains("bind");
    }

    private static boolean mayHavePrincipal(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("user") || lower.contains("admin") || lower.contains("by ") || lower.contains("login")
                || lower.contains("account") || lower.contains("principal") || lower.contains(" for ") || lower.contains("usr");
    }

    private void principal(String line, Pattern pattern, boolean bare, List<Edit> edits) {
        Matcher m = pattern.matcher(line);
        while (m.find()) {
            int start = m.start(1);
            int end = m.end(1);
            while (end > start && ".-@\\".indexOf(line.charAt(end - 1)) >= 0) end--;
            String name = line.substring(start, end).strip();
            if (name.isEmpty()) continue;
            end = start + line.substring(start, end).stripTrailing().length();
            if (written(name) || name.startsWith("<") || EstateDictionary.ADDRESS_LITERAL.matcher(name).matches()) continue;
            if (name.length() <= 5 && name.chars().allMatch(Character::isDigit)) continue;
            if (bare && STOPWORDS.contains(name.toLowerCase(Locale.ROOT))) continue;
            // The trigger and the name are guarded: "by admin jdoe" masks jdoe once, not "admin" too.
            add(edits, new Edit(start, end, tokens.user(name), PRINCIPAL, bare ? m.start() : start, end));
        }
    }

    // ---------------------------------------------------------------- elg header host

    private void elgHosts(String line, List<Edit> edits) {
        if (line.indexOf("]@") < 0) return;
        Matcher m = ELG_HOST.matcher(line);
        while (m.find()) {
            String host = m.group(1);
            if (written(host) || host.equalsIgnoreCase("localhost")) continue;
            String masked = host.indexOf('.') >= 0 ? labels(host.split("\\.", -1), host.split("\\.", -1).length) : names.label(host);
            if (masked == null || masked.equals(host)) continue;
            add(edits, new Edit(m.start(1), m.end(1), masked, HOST, m.start(1), m.end(1)));
        }
    }

    // ---------------------------------------------------------------- host contexts

    private void hostContexts(String line, List<Edit> edits, List<int[]> enumSpans) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (line.startsWith("Linux ")) {
            Matcher m = SAR_HOST.matcher(line);
            if (m.find()) hostWord(line, m.start(1), m.end(1), true, edits);
        }
        if (lower.contains("host") || lower.contains("device") || lower.contains("gateway") || lower.contains("peer")
                || lower.contains("node") || lower.contains("member") || lower.contains("server") || lower.contains("firewall")
                || lower.contains("router") || lower.contains("switch") || lower.contains("appliance")
                || lower.contains("neighbor") || lower.contains("neighbour") || lower.contains("nexthop")) {
            hostKeyword(line, P_HOST_KEYWORD, true, edits, enumSpans);
        }
        if (lower.contains("from") || lower.contains("to")) hostKeyword(line, P_FROM_TO, false, edits, enumSpans);
        if (line.indexOf('(') >= 0) hostKeyword(line, P_PAREN_HOST, true, edits, enumSpans);
        hostKeyword(line, P_HOST_BEFORE_STATE, true, edits, enumSpans);
        hostKeyword(line, P_CLAUSE_HOST_STATE, true, edits, enumSpans);
        hostKeyword(line, P_HOST_PREP_OR_VERB, false, edits, enumSpans);
    }

    /** Each match; a kept word may itself be a keyword ({@code peer gateway X}), so the search resumes at the word. */
    private void hostKeyword(String line, Pattern pattern, boolean learn, List<Edit> edits, List<int[]> enumSpans) {
        Matcher m = pattern.matcher(line);
        int from = 0;
        while (from < line.length() && m.find(from)) {
            int nextWhenKept = m.start(1) > m.start() && m.start(1) > from ? m.start(1) : m.end(1);
            if (inSpans(enumSpans, m.start(1))) {
                from = nextWhenKept;
                continue;
            }
            from = hostWord(line, m.start(1), m.end(1), learn, edits) ? m.end(1) : nextWhenKept;
        }
    }

    /** Masks the word as a host unless it is a kept word or another rule's shape; true when masked. */
    private boolean hostWord(String line, int start, int end, boolean learn, List<Edit> edits) {
        int[] t = trim(line, start, end, "_-");
        if (t[1] - t[0] < 3) return false;
        String word = line.substring(t[0], t[1]);
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || STOPWORDS.contains(lower) || HOST_CONTEXT_WORDS.contains(lower)
                || DATE_WORDS.contains(lower) || written(word) || EXTENSIONS.contains(lower)) return false;
        if (INTERFACE.matcher(word).matches() || PROTOCOL_PART.matcher(word).matches() || VERSION.matcher(word).matches()
                || SIZE_OR_UNIT.matcher(word).matches() || ORDINAL_TOKEN.matcher(word).matches()) {
            return false;
        }
        if (isJsonKey(line, t[0], t[1])) return false;
        if (word.indexOf('-') >= 0 && allowlisted(word)) return false;
        if (serialShaped(word)) return false;
        if (keyed(line, t[1])) return false;
        int before = edits.size();
        add(edits, new Edit(t[0], t[1], names.label(word), HOST_CONTEXT, t[0], t[1]));
        if (edits.size() == before) return false;
        if (learn && learned.size() < MAX_LEARNED) learned.add(lower);
        return true;
    }

    /** The word is a key ({@code device serial=V}): an {@code =} follows it. */
    private static boolean keyed(String line, int end) {
        int k = end;
        while (k < line.length() && line.charAt(k) == ' ') k++;
        return k < line.length() && line.charAt(k) == '=';
    }

    /** The serial rule's mixed shape (8 or more letters and digits, at least 6 digits): left to that rule. */
    private static boolean serialShaped(String word) {
        if (word.length() < 8) return false;
        int digits = 0;
        for (int k = 0; k < word.length(); k++) {
            char c = word.charAt(k);
            if (c >= '0' && c <= '9') digits++;
            else if (!Character.isLetter(c)) return false;
        }
        return digits >= 6;
    }

    // ---------------------------------------------------------------- token shapes

    private static List<int[]> enumSpans(String line) {
        List<int[]> spans = new ArrayList<>();
        if (line.indexOf('"') < 0) return spans;
        Matcher m = ENUM_FIELD.matcher(line);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (ENUM_KEYS.contains(key)) spans.add(new int[] {m.start(2), m.end(2)});
        }
        return spans;
    }

    private void tokens(String line, List<Edit> edits, List<int[]> enumSpans) {
        int n = line.length();
        int i = 0;
        while (i < n) {
            if (!isRunChar(line.charAt(i))) {
                i++;
                continue;
            }
            int runStart = i;
            while (i < n && isRunChar(line.charAt(i))) i++;
            run(line, runStart, i, edits, enumSpans);
        }
    }

    /** A run of {@code [A-Za-z0-9._-]}: the underscore form first, then each {@code _}-separated part. */
    private void run(String line, int start, int end, List<Edit> edits, List<int[]> enumSpans) {
        int[] t = trim(line, start, end, "._-");
        if (t[0] >= t[1]) return;
        String run = line.substring(t[0], t[1]);
        if (learnedName(run) && !isJsonKey(line, t[0], t[1]) && !inSpans(enumSpans, t[0])) {
            add(edits, new Edit(t[0], t[1], names.label(run), HOST_CONTEXT, t[0], t[1]));
            return;
        }
        if (run.indexOf('_') >= 0 && run.indexOf('.') < 0 && underscoreHost(run) && !isJsonKey(line, t[0], t[1])
                && !inSpans(enumSpans, t[0])) {
            add(edits, new Edit(t[0], t[1], names.label(run), HOST, t[0], t[1]));
            return;
        }
        int s = t[0];
        for (int p = t[0]; p <= t[1]; p++) {
            if (p == t[1] || line.charAt(p) == '_') {
                dotted(line, s, p, edits, enumSpans);
                s = p + 1;
            }
        }
    }

    /** A part without underscores: an FQDN-shaped whole, else each dot-separated label on its own. */
    private void dotted(String line, int start, int end, List<Edit> edits, List<int[]> enumSpans) {
        int[] t = trim(line, start, end, ".-");
        if (t[0] >= t[1]) return;
        String part = line.substring(t[0], t[1]);
        if (part.indexOf('.') >= 0) {
            String[] labels = part.split("\\.", -1);
            int stem = labels.length;
            while (stem > 1 && EXTENSIONS.contains(labels[stem - 1].toLowerCase(Locale.ROOT))) stem--;
            if (stem >= 2 && fqdnShaped(labels, stem)) {
                int stemEnd = t[0];
                for (int k = 0; k < stem; k++) stemEnd += labels[k].length() + (k > 0 ? 1 : 0);
                String stemText = line.substring(t[0], stemEnd);
                if (!VERSION.matcher(stemText).matches() && !MAC_DOTTED.matcher(stemText).matches()) {
                    String masked = labels(labels, stem);
                    if (masked != null && !masked.equals(stemText)) {
                        add(edits, new Edit(t[0], stemEnd, masked, FQDN, t[0], stemEnd));
                    }
                    return;
                }
            }
            if (VERSION.matcher(part).matches()) return;
            int s = t[0];
            for (int p = t[0]; p <= t[1]; p++) {
                if (p == t[1] || line.charAt(p) == '.') {
                    piece(line, s, p, edits, enumSpans);
                    s = p + 1;
                }
            }
            return;
        }
        piece(line, t[0], t[1], edits, enumSpans);
    }

    /** At least two labels with a letter; the last label two characters or more with a letter. */
    private static boolean fqdnShaped(String[] labels, int stem) {
        int withLetter = 0;
        for (int k = 0; k < stem; k++) {
            if (labels[k].isEmpty() || !LABEL.matcher(labels[k]).matches()) return false;
            if (hasLetter(labels[k])) withLetter++;
        }
        String last = labels[stem - 1];
        return withLetter >= 2 && last.length() >= 2 && hasLetter(last);
    }

    /** The first {@code count} labels with every label but the public suffix and earlier tokens masked. */
    private String labels(String[] labels, int count) {
        for (int k = 0; k < count; k++) if (labels[k].isEmpty()) return null;
        String[] stem = java.util.Arrays.copyOf(labels, count);
        int suffix = suffixes.suffixLabels(stem);
        if (suffix >= count) return null;
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < count; k++) {
            if (k > 0) out.append('.');
            String label = stem[k];
            out.append(k >= count - suffix || written(label) ? label : names.label(label));
        }
        return out.toString();
    }

    /** One token without dots or underscores: the hostname shape, else the serial-like shapes. */
    private void piece(String line, int start, int end, List<Edit> edits, List<int[]> enumSpans) {
        int[] t = trim(line, start, end, "-");
        if (t[0] >= t[1]) return;
        String p = line.substring(t[0], t[1]);
        if (written(p)) return;
        if (learnedName(p) && !isJsonKey(line, t[0], t[1]) && !inSpans(enumSpans, t[0])) {
            add(edits, new Edit(t[0], t[1], names.label(p), HOST_CONTEXT, t[0], t[1]));
            return;
        }
        Matcher inner = WRITTEN_TOKEN.matcher(p);
        if (inner.find()) {
            // Around a token an earlier pass wrote, each remaining fragment is checked on its own.
            int s = 0;
            do {
                if (inner.start() > s) piece(line, t[0] + s, t[0] + inner.start(), edits, enumSpans);
                s = inner.end();
            } while (inner.find());
            if (s < p.length()) piece(line, t[0] + s, t[1], edits, enumSpans);
            return;
        }
        boolean enumValue = inSpans(enumSpans, t[0]);
        int letters = 0;
        int digits = 0;
        for (int k = 0; k < p.length(); k++) {
            char c = p.charAt(k);
            if (c >= '0' && c <= '9') digits++;
            else if (Character.isLetter(c)) letters++;
        }
        if (p.indexOf('-') >= 0) {
            if (allowlisted(p)) return;
            if (!enumValue && hostnameShaped(p, letters, digits)) {
                add(edits, new Edit(t[0], t[1], names.label(p), HOST, t[0], t[1]));
                return;
            }
            int s = t[0];
            for (int q = t[0]; q <= t[1]; q++) {
                if (q == t[1] || line.charAt(q) == '-') {
                    if (q > s) serialLike(line, s, q, edits, enumValue);
                    s = q + 1;
                }
            }
            return;
        }
        if (!enumValue && singleWordHostShaped(line, t[0], t[1], p, letters, digits)) {
            add(edits, new Edit(t[0], t[1], names.label(p), HOST, t[0], t[1]));
            return;
        }
        serialLike(line, t[0], t[1], edits, enumValue);
    }

    private static boolean singleWordHostShaped(String line, int start, int end, String p, int letters, int digits) {
        if (p.length() < 4 || p.length() > 63 || !Character.isLetter(p.charAt(0))) return false;
        if (start > 0 && (line.charAt(start - 1) == '_' || line.charAt(start - 1) == '.' || line.charAt(start - 1) == '\\')) {
            return false;
        }
        if (end < line.length() && (line.charAt(end) == '_' || line.charAt(end) == '.')) return false;
        if (isJsonKey(line, start, end)) return false;
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || STOPWORDS.contains(lower) || HOST_CONTEXT_WORDS.contains(lower)
                || DATE_WORDS.contains(lower) || EXTENSIONS.contains(lower)) {
            return false;
        }
        if (INTERFACE.matcher(p).matches() || PROTOCOL_PART.matcher(p).matches() || VERSION.matcher(p).matches()
                || SIZE_OR_UNIT.matcher(p).matches() || ORDINAL_TOKEN.matcher(p).matches()
                || NUMBERED_COMMON_NOUN.matcher(p).matches() || HEX_LITERAL.matcher(p).matches()
                || serialShaped(p)) {
            return false;
        }
        if (digits >= 1 && digits <= 4 && SINGLE_WORD_ALNUM_HOST.matcher(p).matches()) return true;
        return digits == 0 && letters >= 6 && SINGLE_WORD_AFFIX_HOST.matcher(p).matches();
    }

    private static boolean hostnameShaped(String p, int letters, int digits) {
        String[] parts = p.split("-", -1);
        for (String part : parts) if (part.isEmpty()) return false;
        String last = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        return (digits >= 1 && letters >= 2) || ((last.equals("aa") || last.equals("bb")) && letters >= 4);
    }

    /** A hyphenated non-identity shape: a UUID, a MAC, an interface, a protocol or cipher name, a date. */
    private static boolean allowlisted(String p) {
        if (UUID.matcher(p).matches() || MAC_HYPHEN.matcher(p).matches() || INTERFACE.matcher(p).matches()) return true;
        String[] parts = p.split("-", -1);
        for (String part : parts) if (part.isEmpty()) return false;
        return protocolName(parts) || dateShaped(parts);
    }

    private static boolean protocolName(String[] parts) {
        boolean named = false;
        for (String part : parts) {
            if (part.chars().allMatch(Character::isDigit)) continue;
            if (!PROTOCOL_PART.matcher(part).matches()) return false;
            named = true;
        }
        return named;
    }

    private static boolean dateShaped(String[] parts) {
        boolean word = false;
        for (String part : parts) {
            if (part.chars().allMatch(Character::isDigit)) continue;
            if (!DATE_WORDS.contains(part.toLowerCase(Locale.ROOT))) return false;
            word = true;
        }
        return word;
    }

    /** Three to six underscore-joined parts, the first with a letter, the last a cluster-member ordinal ({@code _01}). */
    private static boolean underscoreHost(String run) {
        String[] parts = run.split("_", -1);
        if (parts.length < 3 || parts.length > 6) return false;
        int letters = 0;
        for (String part : parts) {
            if (part.isEmpty() || !part.chars().allMatch(c -> c < 128 && Character.isLetterOrDigit(c))) return false;
            letters += (int) part.chars().filter(Character::isLetter).count();
        }
        if (!Character.isLetter(parts[0].charAt(0)) || letters < 2 || written(run)) return false;
        return ORDINAL.matcher(parts[parts.length - 1]).matches();
    }

    private void serialLike(String line, int start, int end, List<Edit> edits, boolean enumValue) {
        if (enumValue || end - start < 8) return;
        String p = line.substring(start, end);
        if (written(p) || HEX_LITERAL.matcher(p).matches()) return;
        int letters = 0;
        int digits = 0;
        for (int k = 0; k < p.length(); k++) {
            char c = p.charAt(k);
            if (c >= '0' && c <= '9') digits++;
            else if (Character.isLetter(c)) letters++;
            else return;
        }
        boolean digitRun = letters == 0 && digits >= 10;
        boolean mixed = letters >= 1 && digits >= 6;
        if (!digitRun && !mixed) return;
        if (digitRun && keyedNumber(line, start)) return;
        add(edits, new Edit(start, end, tokens.serial(p), SERIAL, start, end));
    }

    /** A digit run right after a timestamp or sequence key is a time or a sequence number, not a serial. */
    private static boolean keyedNumber(String line, int start) {
        String before = line.substring(Math.max(0, start - 40), start);
        return SEQ_BEFORE.matcher(before).find() || TIME_BEFORE.matcher(before).find();
    }

    // ---------------------------------------------------------------- helpers

    /** A name this session learned after a host keyword. */
    private boolean learnedName(String token) {
        return !learned.isEmpty() && learned.contains(token.toLowerCase(Locale.ROOT));
    }

    /** Adds the edit unless it overlaps an accepted edit or its guard (earlier rules win). */
    private static void add(List<Edit> edits, Edit edit) {
        for (Edit e : edits) {
            boolean overlapsReplacement = edit.start() < e.end() && e.start() < edit.end();
            boolean overlapsGuard = edit.start() < e.guardEnd() && e.guardStart() < edit.end();
            if (overlapsReplacement || overlapsGuard) return;
        }
        edits.add(edit);
    }

    /** A token an earlier pass (or this one) wrote: never masked again. */
    private static boolean written(String token) {
        return NameMapper.isLabelToken(token) || IdentityTokens.isToken(token) || PSEUDONYM.matcher(token).matches();
    }

    private static boolean isJsonKey(String line, int start, int end) {
        int s = start > 0 && line.charAt(start - 1) == '@' ? start - 1 : start;
        if (s == 0 || line.charAt(s - 1) != '"' || end >= line.length() || line.charAt(end) != '"') return false;
        int k = end + 1;
        while (k < line.length() && Character.isWhitespace(line.charAt(k))) k++;
        return k < line.length() && line.charAt(k) == ':';
    }

    private static boolean inSpans(List<int[]> spans, int position) {
        for (int[] span : spans) if (position >= span[0] && position < span[1]) return true;
        return false;
    }

    private static int[] trim(String line, int start, int end, String chars) {
        while (start < end && chars.indexOf(line.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(line.charAt(end - 1)) >= 0) end--;
        return new int[] {start, end};
    }

    private static boolean isRunChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
    }

    private static boolean hasLetter(String s) {
        for (int k = 0; k < s.length(); k++) if (Character.isLetter(s.charAt(k))) return true;
        return false;
    }
}
