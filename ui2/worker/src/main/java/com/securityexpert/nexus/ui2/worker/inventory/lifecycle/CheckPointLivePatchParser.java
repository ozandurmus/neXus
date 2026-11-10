package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;

/**
 * Check Point {@code cplp list}: the rows after the {@code ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT} header
 * (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 2). A row is {@code id status mode running/total date time
 * comment...}; the id is the first token with characters outside {@code [A-Za-z0-9_.:-]} replaced by {@code _}, a status
 * outside the closed set is {@code UNKNOWN}, the references are the {@code sk<digits>} and {@code CVE-<year>-<digits>}
 * tokens of the comment. The installed time is read only as {@code yyyy-MM-dd HH:mm[:ss]}; any other form gives none.
 * A recognised header with no rows is COLLECTED with no items. The closing note and the rule line are not rows.
 */
public final class CheckPointLivePatchParser {

    public static final String READ_KIND = "cp_cplp_list";

    private static final Set<String> STATUSES = Set.of("armed", "ready", "reverted", "jumbofix", "unsupp", "nopatch", "nolib");
    private static final Pattern PIDS = Pattern.compile("(\\d{1,9})/(\\d{1,9})");
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern MODE = Pattern.compile("[A-Za-z0-9_-]{1,24}");
    private static final Pattern REF = Pattern.compile("(?<![A-Za-z0-9])(?:(sk\\d+)|(CVE-\\d{4}-\\d{4,7}))(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_ID = Pattern.compile("[^A-Za-z0-9_.:-]");
    private static final int ID_MAX = 80;

    private CheckPointLivePatchParser() {
    }

    /** Never throws. */
    public static LivePatchParse parse(String raw) {
        try {
            return doParse(raw);
        } catch (RuntimeException | StackOverflowError e) {
            return raw == null || raw.isBlank() ? new LivePatchParse(ParseOutcome.EMPTY, List.of(), 0) : LivePatchParse.unparsed();
        }
    }

    private static LivePatchParse doParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LivePatchParse(ParseOutcome.EMPTY, List.of(), 0);
        }
        List<LivePatchItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean header = false;
        int skipped = 0;
        for (String line : LifecycleText.lines(raw)) {
            String t = LifecycleText.collapse(line);
            if (t.isEmpty()) {
                continue;
            }
            String[] tokens = t.split(" ");
            if (!header) {
                header = tokens[0].equalsIgnoreCase("ID(PATCH:PROC)");
                continue;
            }
            if (tokens.length < 5 || !PIDS.matcher(tokens[3]).matches()) {
                continue;
            }
            LivePatchItem item = row(tokens);
            if (item == null) {
                skipped++;
            } else if (seen.add(item.patchId())) {
                items.add(item);
            }
        }
        return header ? new LivePatchParse(ParseOutcome.COLLECTED, items, skipped) : LivePatchParse.unparsed();
    }

    private static LivePatchItem row(String[] tokens) {
        String id = UNSAFE_ID.matcher(tokens[0]).replaceAll("_");
        if (id.length() > ID_MAX) {
            id = id.substring(0, ID_MAX);
        }
        if (id.isEmpty()) {
            return null;
        }
        String statusWord = tokens[1].toLowerCase(Locale.ROOT);
        String status = STATUSES.contains(statusWord) ? statusWord : "UNKNOWN";
        String mode = MODE.matcher(tokens[2]).matches() ? tokens[2] : null;
        Matcher pids = PIDS.matcher(tokens[3]);
        pids.matches();
        Integer running = Integer.valueOf(pids.group(1));
        Integer total = Integer.valueOf(pids.group(2));
        int next = 4;
        LocalDateTime installed = null;
        Matcher date = DATE.matcher(tokens[next]);
        if (date.matches()) {
            next++;
            LocalDate day = null;
            try {
                day = LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
            } catch (java.time.DateTimeException e) {
                day = null;
            }
            Matcher time = next < tokens.length ? TIME.matcher(tokens[next]) : null;
            if (time != null && time.matches()) {
                next++;
                if (day != null) {
                    try {
                        installed = day.atTime(LocalTime.of(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)),
                                time.group(3) == null ? 0 : Integer.parseInt(time.group(3))));
                    } catch (java.time.DateTimeException e) {
                        installed = null;
                    }
                }
            } else if (day != null) {
                installed = day.atStartOfDay();
            }
        }
        StringBuilder comment = new StringBuilder();
        for (int i = next; i < tokens.length; i++) {
            comment.append(tokens[i]).append(' ');
        }
        Set<String> refs = new LinkedHashSet<>();
        Matcher m = REF.matcher(comment);
        while (m.find()) {
            refs.add(m.group(1) != null ? m.group(1).toLowerCase(Locale.ROOT) : m.group(2).toUpperCase(Locale.ROOT));
        }
        return new LivePatchItem(id, status, mode, running, total, installed, List.copyOf(refs), null);
    }
}
