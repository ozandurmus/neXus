package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The document addresses a start document names (contract section 3): the Fortinet feed names one CSAF file per item,
 * the Cisco change list names the files that changed. An address is taken only when it passes
 * {@link AdvisorySources#allowed}; everything else in the start document is ignored. Never throws.
 */
public final class FollowUps {

    /** The Fortinet feed's per-item CSAF link element. */
    private static final Pattern FORTINET_CSAF = Pattern.compile("<csaf:link>\\s*(https://[^<\\s]{1,280})\\s*</csaf:link>");
    /** One Cisco change-list line: a year directory, a file name, a timestamp; quotes optional. */
    private static final Pattern CISCO_LINE = Pattern.compile("^\"?(\\d{4}/cisco-sa-[A-Za-z0-9_-]{1,120}\\.json)\"?,\"?([0-9TZ:.+-]{10,40})\"?$");
    /** A document is handed on only when it names this product family (contract section 3, Cisco selection). */
    public static final String CISCO_ASA_FAMILY = "Adaptive Security Appliance (ASA) Software";

    static final int MAX_FOLLOW_UPS = 5000;
    /** A CVE id as the Check Point resolved-issues pages write it. */
    private static final Pattern CVE_ID = Pattern.compile("(?<![A-Za-z0-9-])CVE-(\\d{4})-(\\d{4,7})(?![0-9])");
    /** At most this many CVE records are requested in one Check Point run (the four pages named 61 on 2026-11-13). */
    static final int MAX_CHECKPOINT_RECORDS = 1000;

    private FollowUps() {
    }

    /** The CSAF addresses named by the Fortinet feed, in feed order, without repeats. */
    public static List<URI> fortinet(byte[] feed) {
        Set<URI> out = new LinkedHashSet<>();
        if (feed == null) {
            return List.of();
        }
        Matcher matcher = FORTINET_CSAF.matcher(new String(feed, StandardCharsets.UTF_8));
        while (matcher.find() && out.size() < MAX_FOLLOW_UPS) {
            URI uri = parse(matcher.group(1));
            if (uri != null && uri.getRawPath() != null && uri.getRawPath().endsWith(".json")
                    && AdvisorySources.allowed(AdvisorySources.FORTINET, uri)) {
                out.add(uri);
            }
        }
        return List.copyOf(out);
    }

    /** The Cisco CSAF files whose change time is at or after {@code since}, newest first as the list gives them. */
    public static List<URI> cisco(byte[] changes, Instant since) {
        List<URI> out = new ArrayList<>();
        if (changes == null || since == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String line : new String(changes, StandardCharsets.UTF_8).split("\\R")) {
            Matcher matcher = CISCO_LINE.matcher(line.strip());
            if (!matcher.matches() || out.size() >= MAX_FOLLOW_UPS) {
                continue;
            }
            Instant changed;
            try {
                changed = Instant.parse(matcher.group(2));
            } catch (DateTimeParseException e) {
                continue;
            }
            if (changed.isBefore(since) || !seen.add(matcher.group(1))) {
                continue;
            }
            URI uri = parse("https://" + AdvisorySources.CISCO.host() + "/.well-known/csaf/" + matcher.group(1));
            if (uri != null && AdvisorySources.allowed(AdvisorySources.CISCO, uri)) {
                out.add(uri);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The CVE records named by the Check Point resolved-issues pages (amendment A-2026-11-13): every CVE id in the pages,
     * in page order without repeats, as its address in the CVE Program's public list
     * ({@code /CVEProject/cvelistV5/main/cves/<year>/<number / 1000>xxx/CVE-<year>-<number>.json}). The address is built
     * from the id alone; nothing about the estate is in it.
     */
    public static List<URI> checkpointRecords(List<byte[]> pages) {
        Set<URI> out = new LinkedHashSet<>();
        if (pages == null) {
            return List.of();
        }
        for (byte[] page : pages) {
            if (page == null) {
                continue;
            }
            Matcher matcher = CVE_ID.matcher(new String(page, StandardCharsets.UTF_8));
            while (matcher.find() && out.size() < MAX_CHECKPOINT_RECORDS) {
                URI uri = cveRecord(matcher.group(1), matcher.group(2));
                if (uri != null && AdvisorySources.allowed(AdvisorySources.CHECKPOINT, uri)) {
                    out.add(uri);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The address of one CVE record in the public list; null for a number outside the list's layout. */
    static URI cveRecord(String year, String number) {
        long n;
        try {
            n = Long.parseLong(number);
        } catch (NumberFormatException e) {
            return null;
        }
        return parse("https://" + AdvisorySources.CVELIST_HOST + AdvisorySources.CVELIST_PREFIX + year + "/" + (n / 1000) + "xxx/CVE-"
                + year + "-" + number + ".json");
    }

    /** True when a fetched Cisco file names the ASA software family; the others are dropped unread. */
    public static boolean namesCiscoAsa(byte[] document) {
        return document != null && new String(document, StandardCharsets.ISO_8859_1).contains(CISCO_ASA_FAMILY);
    }

    private static URI parse(String text) {
        try {
            return new URI(text);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}
