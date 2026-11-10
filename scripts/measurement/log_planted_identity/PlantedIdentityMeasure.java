package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.privacy.EstateDictionarySource;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Planted-identity test (design measurement E5, docs/design/LOG_PLANTED_IDENTITY_MEASUREMENT_2026_11_11.md).
 *
 * <p>A synthetic estate (names, addresses and serials generated here from a fixed seed; nothing is read from any
 * system) is loaded into the same dictionary classes the masked Logs view uses, and a synthetic corpus plants one or
 * two of its identities into log lines of the measured shapes. Every line goes through
 * {@link LogMasking} (the deployed rule set, p0), then a draft of the egress profile of the DRAFT design
 * (LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md section 3.3: p2 as drafted, p3 with Argon finding 1) and a draft of the
 * egress guard (section 3.5: G2 known identity, G3 shape, G4 secret). The program counts, per identity class, shape,
 * known or unknown to the dictionary, and context, how many planted values survive each stage.</p>
 *
 * <p>The profile and the guard exist only here, as a model of the draft: no product code implements them. It prints
 * fixed labels and numbers only ({@link #emit}). No real value is involved.</p>
 */
public final class PlantedIdentityMeasure {

    static Set<String> tech = new HashSet<>();
    static Set<String> english = new HashSet<>();
    static int linesPerCell = 5;
    static int poolSize = 24;
    static long seed = 20261111L;
    static final String HDR_DEVICE = "zeta-fw-01";
    static final Set<String> USED = new HashSet<>();
    static final String[] ENGLISH_NAMES = {"falcon", "harbor", "summit", "meadow", "copper", "anchor", "lantern", "orchard",
            "pebble", "quartz", "saffron", "thistle", "willow", "walnut", "velvet", "raven", "marble", "cedar", "ember",
            "glacier", "juniper", "kestrel", "lagoon", "maple", "nectar", "oyster", "prairie", "ripple", "sparrow", "tundra"};
    static final String[] VOCAB_NAMES = {"proxy", "backup", "monitor", "operator", "guest", "internal", "external", "partner",
            "vendor", "finance", "support", "testing", "primary", "standby", "gateway", "cluster", "production", "staging"};

    // ------------------------------------------------------------------ the classes under test
    record Spec(String cls, String kind, String[] shapes, boolean knownable) {
    }

    static final List<Spec> SPECS = List.of(
            new Spec("admin", "USER", new String[] {"plain", "english_word", "dotted", "service", "name_digits", "vocab_word", "turkish_plain"}, true),
            new Spec("policy", "POLICY", new String[] {"snake_year", "hyphen", "spaced", "english_phrase", "vocab_word", "turkish_spaced"}, true),
            new Spec("rule", "RULE", new String[] {"snake", "spaced_sentence", "hyphen", "vocab_word"}, true),
            new Spec("object", "OBJECT", new String[] {"net_ip", "host_obj", "group", "slash_pair", "plus_pair", "plain", "vocab_word"}, true),
            new Spec("zone", "ZONE", new String[] {"hyphen", "snake", "plain", "vocab_word"}, true),
            new Spec("vsys", "VSYS", new String[] {"vs_word", "display_spaced", "plain", "vocab_word"}, true),
            new Spec("domain", "DOMAIN", new String[] {"prefixed", "hyphen", "vocab_word"}, true),
            new Spec("host", "HOST", new String[] {"label", "label_nn", "fqdn"}, true),
            new Spec("device", "DEVICE", new String[] {"site_role_nn", "cluster_member", "plain"}, true),
            new Spec("serial", "SERIAL", new String[] {"pan_digits", "cp_alnum", "hex_like"}, true),
            new Spec("addr", "ADDRESS", new String[] {"v4_private_10", "v4_private_172", "v4_private_192", "v4_public"}, true),
            new Spec("net", "NONE", new String[] {"v4_cidr", "v6_global", "v6_ula", "v6_mapped", "mac_colon", "mac_dash",
                    "mac_cisco", "email", "fqdn_public", "fqdn_internal", "url_host", "enc_dash", "enc_underscore", "enc_ptr",
                    "enc_hex", "enc_decimal"}, false),
            new Spec("secret", "NONE", new String[] {"password", "community", "psk", "bearer", "api_hex", "private_key"}, false));

    // ------------------------------------------------------------------ value generators
    static final String[] ONSET = {"b", "d", "f", "g", "k", "l", "m", "n", "p", "r", "s", "t", "v", "z", "br", "dr", "kr", "pl",
            "st", "tr", "vl", "gr", "sk"};
    static final String[] VOWEL = {"a", "e", "i", "o", "u", "ai", "ou", "ea", "ia"};
    static final String[] CODA = {"", "n", "r", "l", "s", "m", "x", "th", "rd", "nt", "k"};

    static String syl(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(ONSET[r.nextInt(ONSET.length)]).append(VOWEL[r.nextInt(VOWEL.length)]);
            if (i == n - 1 || r.nextInt(3) == 0) sb.append(CODA[r.nextInt(CODA.length)]);
        }
        return sb.toString();
    }

    /** A pronounceable invented word of 5 to 9 letters that is neither an English nor a technical word. */
    static String w(Random r) {
        for (int tries = 0; tries < 200; tries++) {
            String s = syl(r, 2 + r.nextInt(2));
            if (s.length() >= 5 && s.length() <= 9 && !english.contains(s) && !tech.contains(s)) return s;
        }
        return "qzvxkrth";
    }

    static final Locale TR = Locale.forLanguageTag("tr-TR");

    /** An invented word with Turkish letters (dotless i, s-cedilla, g-breve, o and u umlaut, c-cedilla). */
    static String turkish(Random r) {
        String base = w(r);
        char[] map = {'\u0131', '\u015f', '\u011f', '\u00f6', '\u00fc', '\u00e7'};
        StringBuilder sb = new StringBuilder(base);
        int changes = 0;
        for (int i = 0; i < sb.length() && changes < 2; i++) {
            char c = sb.charAt(i);
            if (c == 'a' || c == 'i') {
                sb.setCharAt(i, '\u0131');
                changes++;
            } else if (c == 's' || c == 'k') {
                sb.setCharAt(i, '\u015f');
                changes++;
            } else if (c == 'o') {
                sb.setCharAt(i, '\u00f6');
                changes++;
            } else if (c == 'u') {
                sb.setCharAt(i, '\u00fc');
                changes++;
            }
        }
        if (changes == 0) sb.insert(r.nextInt(sb.length()), map[r.nextInt(map.length)]);
        String v = sb.toString();
        return r.nextInt(3) == 0 ? "i" + v : v;
    }

    static String capTr(String s) {
        return s.substring(0, 1).toUpperCase(TR) + s.substring(1);
    }

    static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Known pools take the even-indexed words and unknown pools the odd ones, so the two never share a value. */
    static boolean knownPool;

    static String eng(Random r) {
        int i = r.nextInt(ENGLISH_NAMES.length / 2) * 2 + (knownPool ? 0 : 1);
        return ENGLISH_NAMES[i];
    }

    static String vocab(Random r) {
        for (int tries = 0; tries < 50; tries++) {
            int i = r.nextInt(VOCAB_NAMES.length / 2) * 2 + (knownPool ? 0 : 1);
            String s = VOCAB_NAMES[i];
            if (tech.contains(s)) return s;
        }
        return knownPool ? "gateway" : "cluster";
    }

    static String digits(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    static String hex(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        return sb.toString();
    }

    static String ip10(Random r) {
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(250));
    }

    static String ip172(Random r) {
        return "172." + (16 + r.nextInt(16)) + "." + r.nextInt(256) + "." + (1 + r.nextInt(250));
    }

    static String ip192(Random r) {
        return "192.168." + r.nextInt(256) + "." + (1 + r.nextInt(250));
    }

    static String ipPub(Random r) {
        int[] first = {23, 34, 52, 64, 78, 91, 104, 141, 185, 203, 45, 62, 88, 151, 193};
        return first[r.nextInt(first.length)] + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(250));
    }

    static String mac(Random r, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int b = i == 0 ? r.nextInt(128) * 2 : r.nextInt(256);
            if (i > 0) sb.append(sep);
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** A value not generated before; the fixed word lists run out, so the last value is reused then. */
    static String unique(Function<Random, String> make, Random r) {
        String v = null;
        for (int tries = 0; tries < 300; tries++) {
            v = make.apply(r);
            if (USED.add(v.toLowerCase(Locale.ROOT))) return v;
        }
        return v;
    }

    static String gen(String cls, String shape, Random r) {
        return unique(rr -> switch (cls + "/" + shape) {
            case "admin/plain" -> w(rr);
            case "admin/english_word" -> eng(rr);
            case "admin/dotted" -> w(rr) + "." + w(rr);
            case "admin/service" -> (rr.nextBoolean() ? "svc-" : "svc_") + w(rr);
            case "admin/name_digits" -> w(rr) + digits(rr, 2);
            case "admin/turkish_plain" -> turkish(rr);
            case "policy/turkish_spaced" -> capTr(turkish(rr)) + " " + capTr(turkish(rr)) + " Politikasi";
            case "admin/vocab_word", "policy/vocab_word", "rule/vocab_word", "object/vocab_word", "zone/vocab_word",
                    "vsys/vocab_word", "domain/vocab_word" -> vocab(rr);
            case "policy/snake_year" -> cap(w(rr)) + "_" + cap(w(rr)) + "_" + (2019 + rr.nextInt(7));
            case "policy/hyphen" -> cap(w(rr)) + "-" + cap(w(rr));
            case "policy/spaced" -> cap(w(rr)) + " " + cap(w(rr)) + " Policy";
            case "policy/english_phrase" -> cap(eng(rr)) + " " + cap(eng(rr));
            case "rule/snake" -> "Allow_" + cap(w(rr)) + "_Access";
            case "rule/spaced_sentence" -> "Permit " + w(rr) + " to " + w(rr);
            case "rule/hyphen" -> "Block-" + cap(w(rr));
            case "object/net_ip" -> "Net_" + ip10(rr) + "_" + (16 + rr.nextInt(14));
            case "object/host_obj" -> "srv-" + w(rr) + "-" + digits(rr, 2);
            case "object/group" -> "grp_" + w(rr) + "_users";
            case "object/slash_pair" -> "Net_" + cap(w(rr)) + "/" + cap(w(rr));
            case "object/plus_pair" -> cap(w(rr)) + "+" + cap(w(rr));
            case "object/plain" -> w(rr);
            case "zone/hyphen" -> cap(w(rr)) + "-" + cap(w(rr));
            case "zone/snake" -> "Zone_" + cap(w(rr));
            case "zone/plain" -> cap(w(rr));
            case "vsys/vs_word" -> "VS_" + cap(w(rr));
            case "vsys/display_spaced" -> cap(w(rr)) + " " + cap(w(rr));
            case "vsys/plain" -> w(rr);
            case "domain/prefixed" -> "Domain_" + cap(w(rr));
            case "domain/hyphen" -> "EMEA-" + cap(w(rr));
            case "host/label" -> w(rr) + digits(rr, 2);
            case "host/label_nn" -> w(rr) + "-" + digits(rr, 2);
            case "host/fqdn" -> w(rr) + "." + w(rr) + ".net";
            case "device/site_role_nn" -> w(rr) + "-fw-" + digits(rr, 2);
            case "device/cluster_member" -> w(rr) + "-cl-" + (rr.nextBoolean() ? "aa" : "bb");
            case "device/plain" -> w(rr) + "fw";
            case "serial/pan_digits" -> "0" + digits(rr, 11 + rr.nextInt(4));
            case "serial/cp_alnum" -> digits(rr, 4) + (char) ('A' + rr.nextInt(26)) + (char) ('A' + rr.nextInt(26)) + digits(rr, 6);
            case "serial/hex_like" -> hex(rr, 12).toUpperCase(Locale.ROOT);
            case "addr/v4_private_10" -> ip10(rr);
            case "addr/v4_private_172" -> ip172(rr);
            case "addr/v4_private_192" -> ip192(rr);
            case "addr/v4_public" -> ipPub(rr);
            case "net/v4_cidr" -> ip10(rr) + "/" + (16 + rr.nextInt(14));
            case "net/v6_global" -> "2" + hex(rr, 3) + ":" + hex(rr, 4) + ":" + hex(rr, 4) + ":" + hex(rr, 4) + "::" + hex(rr, 3);
            case "net/v6_ula" -> "fd" + hex(rr, 2) + ":" + hex(rr, 4) + ":" + hex(rr, 4) + "::" + hex(rr, 2);
            case "net/v6_mapped" -> "::ffff:" + ip10(rr);
            case "net/mac_colon" -> mac(rr, ":");
            case "net/mac_dash" -> mac(rr, "-");
            case "net/mac_cisco" -> {
                String m = mac(rr, "");
                yield m.substring(0, 4) + "." + m.substring(4, 8) + "." + m.substring(8);
            }
            case "net/email" -> w(rr) + "." + w(rr) + "@" + w(rr) + ".com";
            case "net/fqdn_public" -> w(rr) + "." + w(rr) + ".com";
            case "net/fqdn_internal" -> w(rr) + "." + w(rr) + (rr.nextBoolean() ? ".corp" : ".local");
            case "net/url_host" -> w(rr) + "." + w(rr) + ".org";
            case "net/enc_dash" -> "ip-" + ip10(rr).replace('.', '-');
            case "net/enc_underscore" -> ip10(rr).replace('.', '_');
            case "net/enc_ptr" -> {
                String[] o = ip10(rr).split("\\.");
                yield o[3] + "." + o[2] + "." + o[1] + "." + o[0] + ".in-addr.arpa";
            }
            case "net/enc_hex" -> {
                String[] o = ip10(rr).split("\\.");
                yield "0x" + String.format("%02x%02x%02x%02x", Integer.parseInt(o[0]), Integer.parseInt(o[1]),
                        Integer.parseInt(o[2]), Integer.parseInt(o[3]));
            }
            case "net/enc_decimal" -> {
                String[] o = ip10(rr).split("\\.");
                long v = ((long) Integer.parseInt(o[0]) << 24) | (Integer.parseInt(o[1]) << 16) | (Integer.parseInt(o[2]) << 8)
                        | Integer.parseInt(o[3]);
                yield Long.toString(v);
            }
            // secrets are built at runtime from parts (a literal in the repository would trip the secret scanners)
            case "secret/password" -> cap(w(rr)) + hex(rr, 3) + "!" + w(rr);
            case "secret/community" -> w(rr) + digits(rr, 3) + w(rr);
            case "secret/psk" -> hex(rr, 20);
            case "secret/bearer" -> cap(w(rr)) + hex(rr, 12) + cap(w(rr)) + hex(rr, 12);
            case "secret/api_hex" -> hex(rr, 40);
            case "secret/private_key" -> "BEGIN " + "PRIV" + "ATE KEY";
            default -> throw new IllegalArgumentException(cls + "/" + shape);
        }, r);
    }

    // ------------------------------------------------------------------ contexts
    record Ctx(String name, String template) {
    }

    static final String HDR = "Nov 10 03:12:44 2026 " + HDR_DEVICE + " ";
    static final List<Ctx> CONTEXTS = List.of(
            new Ctx("kv", HDR + "xpand[1234]: connection accepted user={X} service=ssh result=ok"),
            new Ctx("kv_quoted", HDR + "xpand[1234]: session opened for account \"{X}\" on console"),
            new Ctx("after_for", HDR + "sshd[2222]: Accepted publickey for {X} from " + "198.51." + "100.7 port 5050 ssh2"),
            new Ctx("sep_bracket", HDR + "clish[3001]: [{X}] executed show interfaces"),
            new Ctx("sep_pipe", HDR + "clish[3001]: audit | {X} | set hostname done"),
            new Ctx("nonvocab_before", HDR + "xpand[1234]: qzktv {X} wrote 14 entries"),
            new Ctx("sentence", HDR + "cpd[900]: the object {X} was updated by the policy installer"),
            new Ctx("trail_punct", HDR + "fwd[800]: failed to resolve {X}, retrying in 30 seconds."),
            new Ctx("glued_underscore", HDR + "cpwd[700]: loading cfg_{X}_backup from disk"),
            new Ctx("glued_suffix", HDR + "cpwd[700]: instance {X}-01 is not responding"),
            new Ctx("path", HDR + "kernel: audit: file /var/log/{X}/audit.log opened"),
            new Ctx("url_path", HDR + "httpd2[610]: GET /api/{X}/status 200"),
            new Ctx("possessive", HDR + "xpand[1234]: {X}'s session expired after 900 seconds"),
            new Ctx("upper", HDR + "xpand[1234]: login by {XU} timed out"),
            new Ctx("upper_tr", HDR + "xpand[1234]: login by {XT} timed out"),
            new Ctx("upper_plain", HDR + "cpd[900]: the object {XU} was updated by the policy installer"),
            new Ctx("upper_plain_tr", HDR + "cpd[900]: the object {XT} was updated by the policy installer"),
            new Ctx("json_key", "{\"receive_time\":\"2026/11/10 03:12:44\",\"type\":\"SYSTEM\",\"subtype\":\"general\",\"{K}\":\"{X}\",\"severity\":\"informational\"}"),
            new Ctx("json_opaque", "{\"receive_time\":\"2026/11/10 03:12:44\",\"type\":\"SYSTEM\",\"subtype\":\"general\",\"eventid\":\"commit\",\"opaque\":\"Commit job for {X} finished with result OK\"}"),
            new Ctx("elg_text", "[cpd 1234 5678]@" + HDR_DEVICE + "[10 Nov 03:12:44] policy fetch for {X} failed (retry 3)"),
            new Ctx("multi", HDR + "xpand[1234]: {X} and {Y} synchronised after 12 seconds"));

    static final List<Ctx> HEADER_CONTEXTS = List.of(
            new Ctx("syslog_header", "Nov 10 03:12:44 2026 {X} kernel: eth1: link up speed 1000 duplex full"),
            new Ctx("elg_header", "[fwd 4129 8123]@{X}[10 Nov 03:12:44] log_reporter: connected to the log server"));

    static final List<Ctx> SECRET_CONTEXTS = List.of(
            new Ctx("kv_secret", HDR + "xpand[1234]: login failed password={X} for console"),
            new Ctx("colon_secret", HDR + "cpd[900]: token: {X} rejected"),
            new Ctx("cli_secret", HDR + "clish[3001]: set snmp community {X} read-only"),
            new Ctx("psk_line", HDR + "vpnd[500]: ike psk {X} configured for peer"),
            new Ctx("json_secret", "{\"receive_time\":\"2026/11/10 03:12:44\",\"type\":\"SYSTEM\",\"subtype\":\"general\",\"opaque\":\"api key {X} created\"}"),
            new Ctx("url_query", HDR + "httpd2[610]: GET /api/status?key={X} 401"),
            new Ctx("header_auth", HDR + "httpd2[610]: Authorization: Bearer {X} accepted"));

    static String jsonKey(String cls) {
        return switch (cls) {
            case "admin" -> "admin";
            case "vsys" -> "vsys_name";
            case "device" -> "device_name";
            case "host" -> "host";
            case "serial" -> "serial";
            case "addr", "net" -> "src";
            default -> "object";
        };
    }

    // ------------------------------------------------------------------ cases
    record Plant(String cls, String shape, boolean known, String value) {
    }

    record Case(String cell, String line, List<Plant> plants) {
    }

    static final class Estate {
        final List<EstateNameCatalog.Named> flat = new ArrayList<>();
        final List<EstateIdentity> identities = new ArrayList<>();
        final Map<String, String> knownDevices = new HashMap<>();
        final Set<String> knownValues = new LinkedHashSet<>();
        final Map<String, int[]> counts = new TreeMap<>();
    }

    static EstateNameCatalog.Kind kindOf(String k) {
        return EstateNameCatalog.Kind.valueOf(k);
    }

    static long mix(String s) {
        long h = seed;
        for (int i = 0; i < s.length(); i++) h = h * 1099511628211L + s.charAt(i);
        return h;
    }

    /** value pools per cell group; known pools are also loaded into the estate. */
    static Map<String, List<String>> buildPools(Estate estate, TopologyNamePseudonymizer pseudonyms) {
        Map<String, List<String>> pools = new LinkedHashMap<>();
        for (Spec spec : SPECS) {
            for (String shape : spec.shapes()) {
                for (boolean known : new boolean[] {true, false}) {
                    if (known && !spec.knownable()) continue;
                    String key = spec.cls() + "/" + shape + "/" + (known ? "known" : "unknown");
                    Random r = new Random(mix(key));
                    knownPool = known;
                    List<String> values = new ArrayList<>();
                    int n = spec.cls().equals("secret") || spec.cls().equals("net") ? Math.min(poolSize, 12) : poolSize;
                    for (int i = 0; i < n; i++) {
                        String v = gen(spec.cls(), shape, r);
                        values.add(v);
                        if (!known) continue;
                        switch (spec.kind()) {
                            case "DEVICE" -> {
                                String p = pseudonyms.maskDeviceName(v, null);
                                estate.identities.add(EstateIdentity.name(v, p));
                                estate.knownDevices.put(v, p);
                            }
                            case "SERIAL" -> estate.identities.add(EstateIdentity.serial(v));
                            case "ADDRESS" -> estate.identities.add(EstateIdentity.address(v));
                            default -> estate.flat.add(new EstateNameCatalog.Named(kindOf(spec.kind()), v));
                        }
                        // the name catalogue drops names made only of generic words and address-like names by design,
                        // so the guard (which holds only the dictionary's identities) does not know them either
                        boolean dropped = shape.equals("vocab_word") || (spec.cls().equals("object") && shape.equals("net_ip"));
                        if (!dropped) estate.knownValues.add(v.toLowerCase(Locale.ROOT));
                    }
                    pools.put(key, values);
                }
            }
        }
        return pools;
    }

    static boolean hasUnbounded(String line, String value) {
        String[] hay = {line.toLowerCase(Locale.ROOT), line.toLowerCase(TR)};
        String[] needle = {value.toLowerCase(Locale.ROOT), value.toLowerCase(TR)};
        for (String h : hay) for (String n : needle) if (containsBounded(h, n)) return true;
        return false;
    }

    static List<Case> buildCases(Map<String, List<String>> pools) {
        List<Case> cases = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, List<String>> e : pools.entrySet()) {
            String[] k = e.getKey().split("/");
            String cls = k[0];
            String shape = k[1];
            boolean known = k[2].equals("known");
            List<String> pool = e.getValue();
            List<Ctx> contexts = new ArrayList<>();
            if (cls.equals("secret")) contexts.addAll(SECRET_CONTEXTS);
            else {
                contexts.addAll(CONTEXTS);
                if (cls.equals("device") || cls.equals("host")) contexts.addAll(HEADER_CONTEXTS);
            }
            for (int c = 0; c < contexts.size(); c++) {
                Ctx ctx = contexts.get(c);
                if (ctx.name().endsWith("_tr") && !shape.startsWith("turkish")) continue;
                for (int i = 0; i < linesPerCell; i++) {
                    String x = pool.get((c * linesPerCell + i) % pool.size());
                    List<Plant> plants = new ArrayList<>();
                    plants.add(new Plant(cls, shape, known, x));
                    String line = ctx.template().replace("{K}", jsonKey(cls));
                    if (line.contains("{Y}")) {
                        String y = pool.get((c * linesPerCell + i + 7) % pool.size());
                        if (y.equals(x)) y = pool.get((c * linesPerCell + i + 3) % pool.size());
                        plants.add(new Plant(cls, shape, known, y));
                        line = line.replace("{Y}", y);
                    }
                    line = line.replace("{XU}", x.toUpperCase(Locale.ROOT)).replace("{XT}", x.toUpperCase(TR)).replace("{X}", x);
                    if (cls.equals("net") && shape.equals("url_host")) line = line.replace(x, "https://" + x + "/login");
                    // the planted value must not also occur in the surrounding template text
                    String rest = line;
                    for (Plant p : plants) rest = rest.replaceAll("(?iu)" + Pattern.quote(p.value()), "\u0001");
                    boolean ambiguous = false;
                    for (Plant p : plants) if (hasUnbounded(rest, p.value())) ambiguous = true;
                    if (ambiguous) {
                        skipped++;
                        continue;
                    }
                    cases.add(new Case(cls + "\t" + shape + "\t" + (known ? "known" : "unknown") + "\t" + ctx.name(), line, plants));
                }
            }
        }
        // control lines: the same contexts with a neutral vocabulary word and no planted value
        List<String> neutral = new ArrayList<>();
        for (String s : VOCAB_NAMES) if (tech.contains(s)) neutral.add(s);
        for (int c = 0; c < CONTEXTS.size(); c++) {
            Ctx ctx = CONTEXTS.get(c);
            if (ctx.name().endsWith("_tr")) continue;
            for (int i = 0; i < 12; i++) {
                String x = neutral.get((c + i) % neutral.size());
                String y = neutral.get((c + i + 5) % neutral.size());
                String line = ctx.template().replace("{K}", "object").replace("{XU}", x.toUpperCase(Locale.ROOT)).replace("{X}", x)
                        .replace("{Y}", y);
                cases.add(new Case("ctl\tneutral\tunknown\t" + ctx.name(), line, List.of()));
            }
        }
        System.setProperty("e5.skipped", Integer.toString(skipped));
        return cases;
    }

    // ------------------------------------------------------------------ egress profile (model of the draft)
    static final Pattern TOKEN = Pattern.compile("[^\\s\"'=,;()\\[\\]{}<>|`]+");
    static final Pattern IFACE = Pattern.compile(
            "(?i)(?:eth\\d+(?:[-/]\\d+)*|bond\\d+|ethernet\\d+/\\d+|ae\\d+|wrpj?\\d+|tunnel|loopback|vlan|ens\\d+\\w*|enp\\d+\\w*|lan\\d+|br\\d+|vpnt\\d+|mgmt|sync)(?:\\.(\\d+))?");
    static final Pattern MASKED = Pattern.compile(
            "(?:(?:h-[0-9a-f]{10}|(?:USR|SN|ID|POL|RUL|OBJ|ZON|VSN|DMN)-[0-9A-F]{10})(?:[@.](?:h-[0-9a-f]{10}|[A-Za-z]{2,24}))*|[A-Z]{2,4}(?:-[A-Z]+)+-M?\\d{1,3}(?:-[A-Z0-9]+)*)");
    static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?|\\d{4}[-/]\\d{2}[-/]\\d{2}(?:T[0-9:.+Z-]*)?|\\d{1,2}[-/][A-Za-z]{3}[-/]\\d{4}");
    static final Pattern VERSION = Pattern.compile("(?i)r\\d{2}(?:\\.\\d+)*|v?\\d+(?:\\.\\d+){1,3}(?:-h\\d+)?");
    static final Pattern ALLNUM = Pattern.compile("\\d+");
    static final Pattern HEXLIT = Pattern.compile("0x[0-9a-fA-F]+");
    static final Pattern IP_SHAPE = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?|[0-9A-Fa-f:]*:[0-9A-Fa-f:]*:[0-9A-Fa-f:]*|[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}");
    static final Pattern PART_SPLIT = Pattern.compile("[._\\-/:@+%#\\\\]+");
    static final Pattern DIGITS = Pattern.compile("\\d+");
    static final Set<String> CODE_WORDS = Set.of("signal", "exit", "errno", "status", "code", "rc", "error");

    static String trimPunct(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && ".:!?*#$,;@\\-".indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && ".:!?*#$,;\\-".indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    /** p2 (drafted = true: English words allowed) or p3 (Argon finding 1: technical vocabulary only, typed numbers). */
    static String egress(String line, boolean drafted, int[] wordStats) {
        StringBuilder sb = new StringBuilder(line.length() + 16);
        Matcher m = TOKEN.matcher(line);
        int last = 0;
        String prev = "";
        while (m.find()) {
            sb.append(line, last, m.start());
            String tok = m.group();
            String core = trimPunct(tok);
            boolean url = core.contains("://");
            String rep = core.isEmpty() ? core : url ? "<URL>" : egressToken(core, prev, drafted);
            if (wordStats != null && !url && core.chars().anyMatch(Character::isLetter)) {
                wordStats[0]++;
                if (rep.equals("<W>")) wordStats[1]++;
            }
            if (core.isEmpty() || rep.equals(core)) sb.append(tok);
            else sb.append(tok.replace(core, rep));
            prev = core.toLowerCase(Locale.ROOT);
            last = m.end();
        }
        sb.append(line, last, line.length());
        return sb.toString();
    }

    static String egressToken(String core, String prev, boolean drafted) {
        if (MASKED.matcher(core).matches() || IP_SHAPE.matcher(core).matches() || TIME.matcher(core).matches()
                || HEXLIT.matcher(core).matches()) return core;
        if (ALLNUM.matcher(core).matches()) {
            if (drafted) return core.length() <= 5 || CODE_WORDS.contains(prev) ? core : "<N>";
            return CODE_WORDS.contains(prev) && core.length() <= 4 ? core : "<N>";
        }
        if (VERSION.matcher(core).matches()) return core;
        Matcher im = IFACE.matcher(core);
        if (im.matches()) {
            if (drafted || im.group(1) == null) return core;
            return core.substring(0, im.start(1)) + "<VLAN>";
        }
        String lower = core.toLowerCase(Locale.ROOT);
        if (tech.contains(lower) || (drafted && english.contains(lower))) return drafted ? core : DIGITS.matcher(core).replaceAll("<N>");
        boolean ok = true;
        for (String part : PART_SPLIT.split(lower)) {
            if (part.isEmpty() || ALLNUM.matcher(part).matches()) continue;
            String letters = part.replaceAll("\\d+$", "");
            if (tech.contains(part) || tech.contains(letters) || (drafted && (english.contains(part) || english.contains(letters)))) continue;
            ok = false;
            break;
        }
        if (!ok) return "<W>";
        return drafted ? core : DIGITS.matcher(core).replaceAll("<N>");
    }

    // ------------------------------------------------------------------ guard (model of the draft, G2, G3, G4)
    static final Set<String> TLDS = Set.of("com", "net", "org", "io", "de", "uk", "fr", "nl", "info", "biz", "edu", "gov", "tr",
            "eu", "us", "ru", "cn", "lan", "local", "corp", "internal", "intranet", "arpa");
    static final Pattern FQDN = Pattern.compile("(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.([a-z]{2,24})");
    static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}");
    static final Pattern IPV4 = Pattern.compile("(?<![0-9A-Za-z.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![0-9A-Za-z]|\\.\\d)");
    static final Pattern IPV6 = Pattern.compile("(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])");
    static final Pattern MACP = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}(?![0-9a-f])|(?<![0-9a-f])[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}(?![0-9a-f])");
    static final Pattern SECRET_KV = Pattern.compile(
            "(?i)(?:password|passwd|secret|token|community|psk|apikey|api[ _-]?key|key)\\s*[=:]?\\s+?[=:]?\\s*(?!<redacted>)([^\\s\"',;<>]{6,})");
    static final Pattern SECRET_KV2 = Pattern.compile(
            "(?i)(?:password|passwd|secret|token|community|psk|apikey|api[ _-]?key|key|bearer)(?:\\s*[=:]\\s*|\\s+)(?!<redacted>)([^\\s\"',;<>]{6,})");
    static final Pattern PEM = Pattern.compile("-{3,}BEGIN [A-Z ]*PRIVATE KEY-{3,}|BEGIN [A-Z ]*PRIVATE KEY");
    static final Pattern B64RUN = Pattern.compile("[A-Za-z0-9+/=_-]{24,}");

    static double entropy(String s) {
        Map<Character, Integer> f = new HashMap<>();
        for (char c : s.toCharArray()) f.merge(c, 1, Integer::sum);
        double h = 0;
        for (int n : f.values()) {
            double p = (double) n / s.length();
            h -= p * Math.log(p) / Math.log(2);
        }
        return h;
    }

    static boolean keptV4(int a, int b) {
        return a == 0 || a == 127 || a >= 224 || (a == 169 && b == 254);
    }

    /** G3: an address-, MAC-, e-mail-, FQDN- or URL-shaped string that is not a masked substitution (alias) or a kept range. */
    static boolean guardShape(String aliased) {
        Matcher v4 = IPV4.matcher(aliased);
        while (v4.find()) {
            int[] o = new int[4];
            boolean ok = true;
            for (int i = 0; i < 4; i++) {
                o[i] = Integer.parseInt(v4.group(i + 1));
                if (o[i] > 255) ok = false;
            }
            if (ok && !keptV4(o[0], o[1])) return true;
        }
        Matcher v6 = IPV6.matcher(aliased);
        while (v6.find()) {
            String g = v6.group().toLowerCase(Locale.ROOT);
            if (g.chars().filter(c -> c == ':').count() >= 3 && g.chars().anyMatch(c -> Character.digit(c, 16) >= 0)
                    && !g.equals("::1") && !g.startsWith("fe80") && !g.startsWith("ff")) return true;
        }
        if (MACP.matcher(aliased).find()) return true;
        if (EMAIL.matcher(aliased).find()) return true;
        if (aliased.contains("://")) {
            int i = aliased.indexOf("://") + 3;
            if (i < aliased.length() && Character.isLetterOrDigit(aliased.charAt(i))) return true;
        }
        Matcher f = FQDN.matcher(aliased);
        while (f.find()) {
            String whole = f.group();
            String tld = f.group(1).toLowerCase(Locale.ROOT);
            if (TLDS.contains(tld) && !VERSION.matcher(whole).matches()) return true;
        }
        return false;
    }

    static boolean guardSecret(String aliased) {
        if (PEM.matcher(aliased).find()) return true;
        Matcher kv = SECRET_KV2.matcher(aliased);
        while (kv.find()) {
            String value = kv.group(1);
            if (!MASKED.matcher(value).matches() && !value.startsWith("<") && value.chars().anyMatch(Character::isLetterOrDigit)
                    && !tech.contains(value.toLowerCase(Locale.ROOT))) return true;
        }
        Matcher b = B64RUN.matcher(aliased);
        while (b.find()) {
            String run = b.group();
            boolean mixed = run.chars().anyMatch(Character::isUpperCase) && run.chars().anyMatch(Character::isLowerCase)
                    && run.chars().anyMatch(Character::isDigit);
            if ((mixed && entropy(run) >= 4.0) || (run.length() >= 32 && run.matches("[0-9a-fA-F]+"))) return true;
        }
        return false;
    }

    static boolean guardKnown(String aliased, Set<String> known) {
        String lower = aliased.toLowerCase(Locale.ROOT);
        for (String k : known) if (lower.contains(k) && containsBounded(lower, k)) return true;
        return false;
    }

    static boolean containsBounded(String hay, String needle) {
        if (needle.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) return false;
            int j = i + needle.length();
            boolean before = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
            boolean after = j >= hay.length() || !Character.isLetterOrDigit(hay.charAt(j));
            if (before && after) return true;
            from = i + 1;
        }
    }

    /** The alias step as the design intends it: tokens the masker changed become aliases; tokens it left alone stay. */
    static String alias(String profiled, Set<String> substituted) {
        StringBuilder sb = new StringBuilder();
        Matcher m = TOKEN.matcher(profiled);
        int last = 0;
        while (m.find()) {
            sb.append(profiled, last, m.start());
            String tok = m.group();
            sb.append(substituted.contains(tok) || substituted.contains(trimPunct(tok)) ? "ALIAS" : tok);
            last = m.end();
        }
        sb.append(profiled, last, profiled.length());
        return sb.toString();
    }

    static Set<String> substitutions(String raw, String masked) {
        Set<String> rawTokens = new HashSet<>();
        Matcher r = TOKEN.matcher(raw);
        while (r.find()) {
            rawTokens.add(r.group());
            rawTokens.add(trimPunct(r.group()));
        }
        Set<String> changed = new HashSet<>();
        Matcher m = TOKEN.matcher(masked);
        while (m.find()) {
            if (!rawTokens.contains(m.group()) && !rawTokens.contains(trimPunct(m.group()))) changed.add(m.group());
        }
        return changed;
    }

    // ------------------------------------------------------------------ run
    static final class Cell {
        long plants, lines, p0, p2, p3, relBlocked, relReleased, blockedLines, falseBlocked, g2, g3, g4, words, suppressed,
                p0Blocked, p0Released, rawP3, rawBlocked, rawReleased;
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            java.util.logging.LogManager.getLogManager().reset();
            java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
        } catch (Throwable ignored) {
        }
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        linesPerCell = Integer.parseInt(a.getOrDefault("--lines-per-cell", "5"));
        poolSize = Integer.parseInt(a.getOrDefault("--pool", "24"));
        seed = Long.parseLong(a.getOrDefault("--seed", "20261111"));
        try {
            tech = loadWords(Path.of(a.get("--tech")), false);
            tech.addAll(FreeTextIdentityRules.STOPWORDS);
            tech.addAll(FreeTextIdentityRules.HOST_CONTEXT_WORDS);
            english = loadWords(Path.of(a.get("--english")), true);
            run(out);
        } catch (Throwable t) {
            emit(out, "fatal", t.getClass().getSimpleName().toLowerCase(Locale.ROOT));
        }
        out.flush();
    }

    static void run(PrintStream out) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest("e5-planted-identity-synthetic-key".getBytes(StandardCharsets.UTF_8));
        TopologyNamePseudonymizer pseudonyms = new TopologyNamePseudonymizer(key);
        Estate estate = new Estate();
        USED.add(HDR_DEVICE);
        // the header device of every template is a known inventory device
        estate.identities.add(EstateIdentity.name(HDR_DEVICE, pseudonyms.maskDeviceName(HDR_DEVICE, null)));
        estate.knownDevices.put(HDR_DEVICE, pseudonyms.maskDeviceName(HDR_DEVICE, null));
        Map<String, List<String>> pools = buildPools(estate, pseudonyms);

        EstateNameCatalog.Source source = new EstateNameCatalog.Source() {
            @Override
            public List<EstateNameCatalog.Named> flatNames() {
                return estate.flat;
            }

            @Override
            public List<String> objectInventoryDocuments() {
                return List.of();
            }

            @Override
            public Map<String, String> policySnapshotVersions() {
                return Map.of();
            }

            @Override
            public java.util.Optional<String[]> policySnapshot(String policyId) {
                return java.util.Optional.empty();
            }
        };
        EstateNameCatalog catalog = new EstateNameCatalog(key, source, Duration.ofHours(4));
        catalog.get();
        EstateNameCatalog.Stats stats = catalog.stats();
        for (Map.Entry<EstateNameCatalog.Kind, Integer> e : new TreeMap<>(stats.kept()).entrySet()) {
            emit(out, "meta", "dictionary_" + e.getKey().name().toLowerCase(Locale.ROOT), String.valueOf(e.getValue()));
        }
        emit(out, "meta", "dictionary_generic_dropped", String.valueOf(stats.genericDropped()));
        emit(out, "meta", "dictionary_shape_dropped", String.valueOf(stats.shapeDropped()));
        EstateDictionarySource devices = new EstateDictionarySource(key, () -> estate.identities, Duration.ofHours(4));
        LogMasking masking = new LogMasking(key, () -> KnownNames.of(estate.knownDevices), devices, catalog);
        emit(out, "meta", "rule_set", LogMasking.RULE_SET.replace('-', '_'));

        List<Case> cases = buildCases(pools);
        emit(out, "meta", "cases", String.valueOf(cases.size()));
        emit(out, "meta", "cases_skipped_ambiguous", System.getProperty("e5.skipped"));
        emit(out, "meta", "tech_vocab_words", String.valueOf(tech.size()));
        emit(out, "meta", "seed", Long.toString(seed));
        int inVocab = 0;
        for (String v : VOCAB_NAMES) if (tech.contains(v)) inVocab++;
        emit(out, "meta", "collision_candidates", String.valueOf(VOCAB_NAMES.length));
        emit(out, "meta", "collision_candidates_in_vocabulary", String.valueOf(inVocab));
        emit(out, "meta", "english_words", String.valueOf(english.size()));

        // G2 knows the identities of the dictionary: names, pieces of names with other characters, addresses, serials
        Set<String> guardKnown = new LinkedHashSet<>();
        for (String v : estate.knownValues) {
            guardKnown.add(v);
            if (v.matches(".*[/+&].*")) for (String piece : v.split("[/+&]+")) if (piece.length() >= 4) guardKnown.add(piece);
        }
        guardKnown.add(HDR_DEVICE);

        Map<String, Cell> cells = new TreeMap<>();
        int[] words = new int[2];
        for (Case c : cases) {
            String masked = masking.session().maskLine(c.line());
            String p2 = egress(masked, true, null);
            int[] ws = new int[2];
            String p3 = egress(masked, false, ws);
            Set<String> substituted = substitutions(c.line(), masked);
            String aliased = alias(p3, substituted);
            boolean g2 = guardKnown(aliased, guardKnown);
            boolean g3 = guardShape(aliased);
            boolean g4 = guardSecret(aliased);
            boolean blocked = g2 || g3 || g4;
            // masking failed open: the raw line (header device replaced, it would block every line) meets the profile and guard
            String rawIn = c.line().replace(HDR_DEVICE, "HDRDEV");
            String rawP3 = egress(rawIn, false, null);
            boolean blockedRaw = guardKnown(rawP3, guardKnown) || guardShape(rawP3) || guardSecret(rawP3);
            String aliased0 = alias(masked, substituted);
            boolean blocked0 = guardKnown(aliased0, guardKnown) || guardShape(aliased0) || guardSecret(aliased0);
            Cell cell = cells.computeIfAbsent(c.cell(), x -> new Cell());
            cell.lines++;
            cell.words += ws[0];
            cell.suppressed += ws[1];
            if (blocked) cell.blockedLines++;
            if (g2) cell.g2++;
            if (g3) cell.g3++;
            if (g4) cell.g4++;
            boolean any3 = false;
            for (Plant p : c.plants()) {
                cell.plants++;
                if (hasUnbounded(masked, p.value())) {
                    cell.p0++;
                    if (blocked0) cell.p0Blocked++;
                    else cell.p0Released++;
                }
                if (hasUnbounded(p2, p.value())) cell.p2++;
                if (hasUnbounded(rawP3, p.value())) {
                    cell.rawP3++;
                    if (blockedRaw) cell.rawBlocked++;
                    else cell.rawReleased++;
                }
                if (hasUnbounded(p3, p.value())) {
                    cell.p3++;
                    any3 = true;
                    if (blocked) cell.relBlocked++;
                    else cell.relReleased++;
                }
            }
            if (blocked && !any3) cell.falseBlocked++;
        }
        // self-check of the harness: a neutral control line must keep its header device masked
        String ctl = masking.session().maskLine(HDR + "xpand[1234]: connection accepted user=primary service=ssh result=ok");
        emit(out, "meta", "selfcheck_header_device_masked", hasUnbounded(ctl, HDR_DEVICE) ? "0" : "1");
        for (Map.Entry<String, Cell> e : cells.entrySet()) {
            Cell c = e.getValue();
            String[] f = e.getKey().split("\t");
            emit(out, "cell", f[0], f[1], f[2], f[3], String.valueOf(c.plants), String.valueOf(c.lines), String.valueOf(c.p0),
                    String.valueOf(c.p2), String.valueOf(c.p3), String.valueOf(c.relBlocked), String.valueOf(c.relReleased),
                    String.valueOf(c.blockedLines), String.valueOf(c.falseBlocked), String.valueOf(c.g2), String.valueOf(c.g3),
                    String.valueOf(c.g4), String.valueOf(c.words), String.valueOf(c.suppressed), String.valueOf(c.p0Blocked),
                    String.valueOf(c.p0Released), String.valueOf(c.rawP3), String.valueOf(c.rawBlocked), String.valueOf(c.rawReleased));
        }
    }

    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");

    static void emit(PrintStream out, String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('\t');
            String f = fields[i] == null ? "null" : fields[i];
            sb.append(SAFE.matcher(f).matches() ? f : "invalid_field");
        }
        out.println(sb);
    }

    static Set<String> loadWords(Path p, boolean lettersOnly) throws Exception {
        Set<String> out = new HashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int hashAt = line.indexOf('#');
            String l = (hashAt >= 0 ? line.substring(0, hashAt) : line).strip().toLowerCase(Locale.ROOT);
            if (l.isEmpty()) continue;
            for (String w : l.split("\\s+")) {
                if (lettersOnly && !w.chars().allMatch(c -> c >= 'a' && c <= 'z')) continue;
                if (lettersOnly && w.length() < 2) continue;
                out.add(w);
            }
        }
        return out;
    }

    private PlantedIdentityMeasure() {
    }
}
