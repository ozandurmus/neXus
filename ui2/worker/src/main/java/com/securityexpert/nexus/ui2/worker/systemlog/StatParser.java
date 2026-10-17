package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser of the C1 output (contract section 4.2). Every line is classified by its shape, never by its position:
 * <ul>
 * <li>the device clock is the first line that is only 9 to 11 digits;</li>
 * <li>a stat record is {@code /path inode size mtime} (four fields, the first an absolute path);</li>
 * <li>a {@code stat: cannot stat '<glob>': <reason>} line is an error line, attributed to a family by the glob's
 *     file name;</li>
 * <li>a line that starts with {@code /} but is not a well-formed record is malformed: it is counted, attributed to
 *     the family it could belong to, and not applied at all (no half-applied record);</li>
 * <li>anything else is a non-data line (the banner of 25 of 53 gateways) and is ignored.</li>
 * </ul>
 * The method never throws and never reads past the given string.
 */
public final class StatParser {
    private static final Pattern CLOCK = Pattern.compile("^[0-9]{9,11}$");
    private static final Pattern RECORD = Pattern.compile("^(/\\S+) ([0-9]+) ([0-9]+) ([0-9]+)$");
    private static final Pattern ERROR = Pattern.compile("^stat: cannot (?:stat|statx|lstat) '([^']*)': (.*)$");
    private static final String ENOENT = "No such file or directory";

    private StatParser() {
    }

    public static StatParseResult parse(String output) {
        Long epoch = null;
        Map<String, FileStat> files = new LinkedHashMap<>();
        Set<String> duplicated = new HashSet<>();
        int leading = 0;
        int ignored = 0;
        int errors = 0;
        int malformed = 0;
        Set<SourceFamily> malformedFamilies = EnumSet.noneOf(SourceFamily.class);
        Set<SourceFamily> absent = EnumSet.noneOf(SourceFamily.class);
        Set<SourceFamily> errored = EnumSet.noneOf(SourceFamily.class);

        if (output != null) {
            for (String raw : output.split("\\R", -1)) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (epoch == null && CLOCK.matcher(line).matches()) {
                    epoch = Long.parseLong(line);
                    continue;
                }
                Matcher record = RECORD.matcher(line);
                if (record.matches()) {
                    FileStat stat = toStat(record);
                    if (stat == null) {
                        malformed++;
                        attribute(record.group(1), malformedFamilies);
                    } else if (duplicated.contains(stat.path())) {
                        malformed++;
                        attribute(stat.path(), malformedFamilies);
                    } else if (files.containsKey(stat.path())) {
                        // The same path twice: ambiguous, so neither occurrence is applied.
                        files.remove(stat.path());
                        duplicated.add(stat.path());
                        malformed += 2;
                        attribute(stat.path(), malformedFamilies);
                    } else {
                        files.put(stat.path(), stat);
                    }
                    continue;
                }
                Matcher error = ERROR.matcher(line);
                if (error.matches()) {
                    errors++;
                    attribute(error.group(1), error.group(2).contains(ENOENT) ? absent : errored);
                    continue;
                }
                if (line.startsWith("/")) {
                    malformed++;
                    attribute(line.split("\\s", 2)[0], malformedFamilies);
                    continue;
                }
                if (epoch == null) {
                    leading++;
                } else {
                    ignored++;
                }
            }
        }
        // A family with an ambiguous path is unreliable; a family with a stat line is not absent.
        return new StatParseResult(epoch, new ArrayList<>(files.values()), leading, ignored, errors, malformed,
                malformedFamilies, absent, errored);
    }

    private static FileStat toStat(Matcher record) {
        try {
            return new FileStat(record.group(1), Long.parseLong(record.group(2)),
                    Long.parseLong(record.group(3)), Long.parseLong(record.group(4)));
        } catch (NumberFormatException e) {
            return null; // a number beyond 63 bits is not a stat value
        }
    }

    /** Attributes a path or glob (trailing {@code *} allowed) to the family whose glob covers its file name. */
    private static void attribute(String pathOrGlob, Set<SourceFamily> into) {
        String name = pathOrGlob.substring(pathOrGlob.lastIndexOf('/') + 1);
        SourceFamily family = SourceFamily.ofFileName(name);
        if (family != null) {
            into.add(family);
        }
    }
}
