package com.securityexpert.nexus.ui2.packetanalysis.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites an OOXML package so its bytes depend only on its content: entries in a fixed order (content types and
 * relationships first, then by name), the same 1980-01-02 timestamp on every entry (set as a local date-time,
 * so the time zone of the JVM cannot change it), no extra fields, a fixed compression level, and the default-locale
 * language tag POI writes into chart titles replaced by the report's own, and the stray value POI writes on the open end
 * of a colour scale removed.
 */
final class DeterministicZip {
    /**
     * 1980-01-02 00:00. Exactly 1980-01-01 00:00 is the DOS epoch itself: the JDK then adds an extended-timestamp extra
     * field computed in the JVM's time zone, which would make the bytes depend on the zone.
     */
    private static final LocalDateTime FIXED = LocalDateTime.of(1980, 1, 2, 0, 0, 0);

    private DeterministicZip() {
    }

    static byte[] repack(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (!e.isDirectory()) {
                    entries.put(e.getName(), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> names = new ArrayList<>(entries.keySet());
        names.sort(Comparator.<String>comparingInt(DeterministicZip::rank).thenComparing(Comparator.naturalOrder()));
        ByteArrayOutputStream out = new ByteArrayOutputStream(zip.length);
        try (ZipOutputStream archive = new ZipOutputStream(out)) {
            archive.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTimeLocal(FIXED);
                archive.putNextEntry(entry);
                archive.write(normalized(name, entries.get(name)));
                archive.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * POI stamps each chart title run with the JVM's default locale ({@code lang="en-TR"}); the report's language is fixed.
     */
    private static byte[] normalized(String name, byte[] content) {
        if (name.startsWith("xl/charts/") && name.endsWith(".xml")) {
            return new String(content, java.nio.charset.StandardCharsets.UTF_8).replaceAll("lang=\"[^\"]*\"", "lang=\"tr-TR\"")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
            // POI leaves a stray value on the open end of a colour scale; Excel writes none
            return new String(content, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("<cfvo type=\"max\" val=\"50\"/>", "<cfvo type=\"max\"/>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return content;
    }

    private static int rank(String name) {
        if ("[Content_Types].xml".equals(name)) {
            return 0;
        }
        if ("_rels/.rels".equals(name)) {
            return 1;
        }
        return 2;
    }
}
