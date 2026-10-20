package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSystemLogCollect;
import com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter;

/**
 * The in-memory half of the Spike Detective data collection (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §8.3 as amended by
 * the PO on 2026-10-20): which members of one data directory are fetched, the per-class member summary stored in
 * {@code system_log_spike_directory.members}, and the neXus-side tar.gz the fetched members are stored in (§5.1: "Spike
 * files of one data directory go into one neXus-side tar.gz").
 *
 * <p>The member summary holds name CLASSES only (closed vocabulary of {@link CpSyslogMeasurementSummary#classify}), each
 * key prefixed by its entry type ({@code file:}, {@code dir:}, {@code link:}, {@code other:}). Clear names exist only in
 * the archive's {@value #MANIFEST} member, inside the encrypted artefact (§5.2: "The clear name exists only inside the
 * encrypted artefact, in its manifest header").</p>
 */
final class CpSpikeData {
    static final String MANIFEST = "MANIFEST.json";
    static final String MANIFEST_SCHEMA = "cp_spike_data_manifest_v1";
    private static final int TAR_NAME_MAX = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    private CpSpikeData() {}

    /** True when a C5 entry is a regular file on the amended fetch list. */
    static boolean fetchable(CpLsListing.Entry e) {
        return e.regularFile() && CpSystemLogCollect.spikeMemberFetchable(e.name());
    }

    /** The fetch-list members of one C5 listing, in name order (deterministic fetch order). */
    static List<CpLsListing.Entry> fetchList(List<CpLsListing.Entry> members) {
        List<CpLsListing.Entry> list = new ArrayList<>(members.stream().filter(CpSpikeData::fetchable).toList());
        list.sort(Comparator.comparing(CpLsListing.Entry::name));
        return list;
    }

    static String typePrefix(char type) {
        return type == '-' ? "file:" : type == 'd' ? "dir:" : type == 'l' ? "link:" : "other:";
    }

    /**
     * {@code {"file:perf_X_NNN.log": {"count": 1, "bytes": 10851, "fetched": 1}, ...}}: per class, never a name.
     *
     * @param fetched the member names whose bytes were stored
     */
    static String membersJson(List<CpLsListing.Entry> members, Set<String> fetched,
            CpSyslogMeasurementSummary.KeyedTokens tokens) {
        Map<String, long[]> classes = new TreeMap<>();   // count, bytes, fetched
        for (CpLsListing.Entry e : members) {
            String key = typePrefix(e.type()) + CpSyslogMeasurementSummary.classify(e.name(), tokens).clazz();
            if (!classes.containsKey(key) && classes.size() >= CpSyslogMeasurementSummary.MAX_CLASSES)
                key = "other:" + CpSyslogMeasurementSummary.OTHER;
            long[] agg = classes.computeIfAbsent(key, k -> new long[3]);
            agg[0]++;
            agg[1] += e.size();
            if (e.regularFile() && fetched.contains(e.name())) agg[2]++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        classes.forEach((key, agg) -> {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("count", agg[0]);
            stats.put("bytes", agg[1]);
            stats.put("fetched", agg[2]);
            out.put(key, stats);
        });
        try {
            return JSON.writeValueAsString(out);
        } catch (IOException impossible) {
            return "{}";
        }
    }

    /** One fetched member: its listed entry and the bytes as received. */
    record Fetched(CpLsListing.Entry entry, byte[] bytes) {
    }

    /**
     * The archive of one directory: {@value #MANIFEST} first (directory name and mtime, then every member C5 listed with
     * its type, size, mtime, whether it was fetched, the tar member name it is stored under and the SHA-256 of the bytes
     * as received), then each fetched member as received. A member whose name does not fit a tar header is stored as
     * {@code member-NNN} and the manifest maps it back. The archive lives only inside the encrypted artefact.
     */
    static byte[] archive(String directoryName, long directoryMtime, List<CpLsListing.Entry> members, List<Fetched> fetched)
            throws IOException {
        Map<String, Fetched> byName = new LinkedHashMap<>();
        for (Fetched f : fetched) byName.put(f.entry().name(), f);
        List<Map<String, Object>> listing = new ArrayList<>();
        Map<String, String> tarNames = new LinkedHashMap<>();
        int index = 0;
        for (CpLsListing.Entry e : members) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.name());
            m.put("type", String.valueOf(e.type()));
            m.put("size", e.size());
            m.put("mtime", e.mtime());
            Fetched f = e.regularFile() ? byName.get(e.name()) : null;
            m.put("fetched", f != null);
            if (f != null) {
                String tarName = e.name().getBytes(StandardCharsets.UTF_8).length < TAR_NAME_MAX && !MANIFEST.equals(e.name())
                        ? e.name() : String.format("member-%03d", index);
                tarNames.put(e.name(), tarName);
                m.put("archiveMember", tarName);
                m.put("receivedBytes", f.bytes().length);
                m.put("sha256", sha256(f.bytes()));
            }
            listing.add(m);
            index++;
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", MANIFEST_SCHEMA);
        manifest.put("directory", directoryName);
        manifest.put("directoryMtime", directoryMtime);
        manifest.put("members", listing);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarWriter tar = new TarWriter(new GZIPOutputStream(out))) {
            tar.file(MANIFEST, JSON.writeValueAsBytes(manifest));
            for (Fetched f : fetched) tar.file(tarNames.get(f.entry().name()), f.bytes());
        }
        return out.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
