package com.securityexpert.nexus.ui2.sanitizer.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The ICANN section of the Public Suffix List, read from the JDK's own copy
 * ({@code $JAVA_HOME/lib/security/public_suffix_list.dat}, one zip entry per TLD, ICANN rules indented by one space)
 * so no list is vendored here. The special-use and common private-network suffixes are always added. When the JDK
 * file is missing, a conservative built-in set is used (generic TLDs plus every two-letter country code).
 *
 * <p>{@link #suffixLabels(String[])} answers how many trailing labels of a name form its public suffix, with the PSL
 * wildcard ({@code *.ck}) and exception ({@code !www.ck}) rules; zero means the last label is not a known suffix.</p>
 */
public final class PublicSuffixes {

    private static final Set<String> SPECIAL = Set.of("test", "example", "invalid", "localhost", "local", "onion",
            "internal", "lan", "corp", "home", "intranet", "localdomain", "home.arpa", "in-addr.arpa", "ip6.arpa");
    private static final Set<String> FALLBACK_GENERIC = Set.of("com", "net", "org", "edu", "gov", "mil", "int", "arpa",
            "info", "biz", "name", "pro", "io", "dev", "app", "cloud", "online", "site", "tech", "xyz");

    private static final PublicSuffixes INSTANCE = load();

    private final Set<String> rules;
    private final Set<String> tlds;
    private final boolean fallback;

    private PublicSuffixes(Set<String> rules, Set<String> tlds, boolean fallback) {
        this.rules = rules;
        this.tlds = tlds;
        this.fallback = fallback;
    }

    public static PublicSuffixes get() {
        return INSTANCE;
    }

    /** True when the JDK list was unavailable and the built-in fallback is in use. */
    public boolean isFallback() {
        return fallback;
    }

    /** True when {@code label} (any case, Unicode or punycode) is a top-level public suffix. */
    public boolean isTld(String label) {
        String ascii = ascii(label);
        if (ascii == null) return false;
        if (fallback) return tlds.contains(ascii) || ascii.matches("[a-z]{2}");
        return tlds.contains(ascii);
    }

    /** Number of trailing labels that are the public suffix (0 when the last label is no known TLD). */
    public int suffixLabels(String[] labels) {
        int n = labels.length;
        if (n == 0) return 0;
        String[] ascii = new String[n];
        for (int i = 0; i < n; i++) {
            ascii[i] = ascii(labels[i]);
            if (ascii[i] == null) return 0;
        }
        if (!isTld(labels[n - 1])) return 0;
        int best = 1;
        for (int k = 2; k <= n; k++) {
            String candidate = join(ascii, n - k);
            String parent = join(ascii, n - k + 1);
            if (rules.contains("!" + candidate)) {
                break;
            }
            if (rules.contains(candidate) || rules.contains("*." + parent)) {
                best = k;
            }
        }
        return best;
    }

    private static String join(String[] labels, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < labels.length; i++) {
            if (i > from) b.append('.');
            b.append(labels[i]);
        }
        return b.toString();
    }

    /** Lower-case ASCII (punycode) form of one label, or {@code null} when it is not a valid label. */
    static String ascii(String label) {
        if (label.isEmpty()) return null;
        boolean plain = true;
        for (int i = 0; i < label.length(); i++) {
            if (label.charAt(i) > 0x7f) {
                plain = false;
                break;
            }
        }
        if (plain) return label.toLowerCase(Locale.ROOT);
        try {
            return IDN.toASCII(label, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static PublicSuffixes load() {
        Path file = Path.of(System.getProperty("java.home", ""), "lib", "security", "public_suffix_list.dat");
        Set<String> rules = new HashSet<>(SPECIAL);
        Set<String> tlds = new HashSet<>();
        for (String special : SPECIAL) {
            if (!special.contains(".")) tlds.add(special);
        }
        if (Files.isRegularFile(file)) {
            try (ZipFile zip = new ZipFile(file.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    try (InputStream in = zip.getInputStream(entry)) {
                        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        for (String line : text.split("\n")) {
                            // ICANN rules carry one leading space; private-domain rules (no space) are ignored.
                            if (!line.startsWith(" ")) continue;
                            String rule = line.strip().toLowerCase(Locale.ROOT);
                            if (rule.isEmpty()) continue;
                            String bare = rule.startsWith("!") ? rule.substring(1) : rule.startsWith("*.") ? rule.substring(2) : rule;
                            String asciiRule = toAsciiRule(bare);
                            if (asciiRule == null) continue;
                            String prefix = rule.startsWith("!") ? "!" : rule.startsWith("*.") ? "*." : "";
                            rules.add(prefix + asciiRule);
                            if (!asciiRule.contains(".")) tlds.add(asciiRule);
                        }
                    }
                }
                if (tlds.size() > 200) return new PublicSuffixes(Set.copyOf(rules), Set.copyOf(tlds), false);
            } catch (IOException | RuntimeException unreadable) {
                // fall through to the built-in set
            }
        }
        tlds.addAll(FALLBACK_GENERIC);
        rules.addAll(FALLBACK_GENERIC);
        return new PublicSuffixes(Set.copyOf(rules), Set.copyOf(tlds), true);
    }

    private static String toAsciiRule(String rule) {
        String[] labels = rule.split("\\.");
        StringBuilder b = new StringBuilder();
        for (String label : labels) {
            String a = ascii(label);
            if (a == null) return null;
            if (b.length() > 0) b.append('.');
            b.append(a);
        }
        return b.toString();
    }
}
