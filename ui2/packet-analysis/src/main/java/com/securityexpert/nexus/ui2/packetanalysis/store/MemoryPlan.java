package com.securityexpert.nexus.ui2.packetanalysis.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The memory plan of one analysis process: how the container (pod) limit is divided between the JVM heap, DuckDB
 * and everything else, and where DuckDB spills when it reaches its limit.
 *
 * <pre>
 *   container limit  >=  JVM heap (-Xmx)  +  DuckDB memory_limit  +  reserve (native, off-heap, tshark child)
 * </pre>
 *
 * <p>DuckDB's {@code memory_limit} bounds its own buffer manager only. The JVM heap, metaspace, code cache, direct
 * buffers, thread stacks, the DuckDB JDBC natives outside the buffer manager and the {@code tshark} child process
 * all count against the container limit as well, so the DuckDB limit is never allowed to exceed
 * {@code container - heap - reserve}: a configured value above that ceiling is lowered to it (fail closed). A query
 * that needs more than the limit spills to {@code temp_directory} on the analysis volume. DuckDB's own
 * {@code max_temp_directory_size} is only a soft bound (DuckDB 1.5.6 exceeded it by up to 4.7x in measurements), so
 * {@link SpillGuard} enforces the hard one; a run that reaches either ends cleanly ({@link AnalysisFailure}) instead of
 * being killed by the kernel or filling the volume.
 *
 * <p>Nothing here changes a result: the limits decide only whether a run completes, spills or stops.
 *
 * <p>System properties (all optional; the worker maps its environment onto them):
 * <ul>
 *   <li>{@value #MEMORY_LIMIT_PROPERTY} - DuckDB memory limit in MiB;</li>
 *   <li>{@value #CONTAINER_PROPERTY} - the container limit in MiB when the JVM cannot see it (a command-line run
 *       with {@code --memory-mb}); default: the JVM's view of the container, else physical memory;</li>
 *   <li>{@value #RESERVE_PROPERTY} - the reserve in MiB; default {@code max(2 GiB, container / 8)};</li>
 *   <li>{@value #MAX_TEMP_PROPERTY} - DuckDB's (soft) spill ceiling in MiB; default 40 GiB, lowered to the free
 *       space of the spill volume less {@value #FREE_SPACE_GUARD_MIB} MiB;</li>
 *   <li>{@value SpillGuard#HARD_TEMP_PROPERTY} - the hard spill limit in MiB ({@link SpillGuard}); default twice
 *       the soft ceiling.</li>
 * </ul>
 */
public final class MemoryPlan {

    /** DuckDB memory limit in MiB for every connection when set. */
    public static final String MEMORY_LIMIT_PROPERTY = "packetanalysis.duckdb.memory_limit_mb";
    public static final String CONTAINER_PROPERTY = "packetanalysis.memory.container_mb";
    public static final String RESERVE_PROPERTY = "packetanalysis.memory.reserve_mb";
    public static final String MAX_TEMP_PROPERTY = "packetanalysis.duckdb.max_temp_mb";

    public static final long MIB = 1024L * 1024L;
    public static final long GIB = 1024L * MIB;
    /** The default spill ceiling. */
    public static final long DEFAULT_MAX_TEMP_MIB = 40L * 1024L;
    /** Free space kept on the spill volume when the ceiling is lowered to what is free. */
    public static final long FREE_SPACE_GUARD_MIB = 2048L;
    /** The smallest DuckDB limit the ceiling is lowered to. */
    public static final long MIN_LIMIT_MIB = 512L;
    private static final long MIN_TEMP_MIB = 256L;
    private static final long MIN_RESERVE_MIB = 2048L;
    private static final System.Logger LOG = System.getLogger(MemoryPlan.class.getName());

    private MemoryPlan() {
    }

    /** The container limit in bytes: {@value #CONTAINER_PROPERTY}, else the JVM's view of the container. */
    public static long containerBytes() {
        Long configured = positiveMib(CONTAINER_PROPERTY);
        return configured != null ? configured * MIB : MemoryBudget.systemTotalMemoryBytes();
    }

    /** The reserve in MiB for a container of {@code containerMib}: {@code max(2 GiB, container / 8)} unless set. */
    public static long reserveMib(long containerMib) {
        Long configured = positiveMib(RESERVE_PROPERTY);
        return configured != null ? configured : Math.max(MIN_RESERVE_MIB, containerMib / 8);
    }

    /**
     * The largest DuckDB limit that fits: {@code container - heap - reserve}, in MiB, never below
     * {@value #MIN_LIMIT_MIB}.
     */
    public static long ceilingMib(long containerBytes, long heapBytes, long reserveMib) {
        long container = containerBytes / MIB;
        long heap = heapBytes / MIB;
        return Math.max(MIN_LIMIT_MIB, container - heap - reserveMib);
    }

    /** {@link #ceilingMib(long, long, long)} for this JVM and its configuration. */
    public static long ceilingMib() {
        long container = containerBytes();
        return ceilingMib(container, Runtime.getRuntime().maxMemory(), reserveMib(container / MIB));
    }

    /**
     * The DuckDB limit in MiB to use: {@code requestedMib} (an explicit override), else the system property, else the
     * default of {@code defaultMib}; in every case lowered to the ceiling.
     */
    public static int memoryLimitMib(Integer requestedMib, int defaultMib) {
        long wanted;
        if (requestedMib != null) {
            wanted = requestedMib;
        } else {
            Long configured = positiveMib(MEMORY_LIMIT_PROPERTY);
            wanted = configured != null ? configured : defaultMib;
        }
        return clampMib(wanted);
    }

    /** {@code wantedMib} lowered to the ceiling (a warning is logged when it was). */
    public static int clampMib(long wantedMib) {
        long ceiling = ceilingMib();
        if (wantedMib > ceiling) {
            LOG.log(System.Logger.Level.WARNING,
                    "[PACKET_ANALYSIS] DuckDB memory limit {0} MiB lowered to {1} MiB (container - heap - reserve)",
                    wantedMib, ceiling);
            return (int) ceiling;
        }
        return (int) wantedMib;
    }

    /**
     * The spill ceiling in MiB for the volume holding {@code spill}: the configured value (default 40 GiB), lowered
     * to the volume's usable space less the guard, never below {@value #MIN_TEMP_MIB}.
     */
    public static long maxTempMib(Path spill) {
        Long configured = positiveMib(MAX_TEMP_PROPERTY);
        return maxTempMib(configured != null ? configured : DEFAULT_MAX_TEMP_MIB, usableMib(spill));
    }

    /** The spill ceiling for a configured size and a volume with {@code usableMib} free (negative: unknown). */
    public static long maxTempMib(long configuredMib, long usableMib) {
        long ceiling = configuredMib;
        if (usableMib >= 0) {
            ceiling = Math.min(ceiling, usableMib - FREE_SPACE_GUARD_MIB);
        }
        return Math.max(MIN_TEMP_MIB, ceiling);
    }

    /**
     * Applies {@code temp_directory} and {@code max_temp_directory_size} to the connection properties. Every
     * connection to one case database must carry the same values (DuckDB shares the instance).
     */
    public static void applySpill(Properties props, Path spill) {
        props.setProperty("temp_directory", spill.toString());
        props.setProperty("max_temp_directory_size", maxTempMib(spill) + "MiB");
    }

    /**
     * The spill directory of a case: {@code <case>/.spill} with the case directory resolved to its real path when it
     * exists, so every connection of the case names the same directory.
     */
    public static Path spillDirectory(Path caseDir) {
        Path dir = caseDir.toAbsolutePath().normalize();
        try {
            dir = dir.toRealPath();
        } catch (IOException | RuntimeException e) {
            // not created yet: the absolute path stands
        }
        return dir.resolve(".spill");
    }

    /** A one-line description of the plan for the worker log and run.json. */
    public static String describe() {
        long container = containerBytes();
        long containerMib = container / MIB;
        long reserve = reserveMib(containerMib);
        long heap = Runtime.getRuntime().maxMemory() / MIB;
        return "container " + containerMib + " MiB = heap " + heap + " + DuckDB ceiling "
                + ceilingMib(container, Runtime.getRuntime().maxMemory(), reserve) + " + reserve " + reserve
                + " (configured DuckDB limit " + System.getProperty(MEMORY_LIMIT_PROPERTY, "default")
                + ", spill ceiling " + System.getProperty(MAX_TEMP_PROPERTY, DEFAULT_MAX_TEMP_MIB + "") + " MiB)";
    }

    /** True when {@code duckdbMib + heapMib + reserveMib <= containerMib}: the arithmetic of a deployment. */
    public static boolean fits(long containerMib, long heapMib, long duckdbMib, long reserveMib) {
        return duckdbMib + heapMib + reserveMib <= containerMib;
    }

    private static long usableMib(Path spill) {
        Path probe = spill;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return -1;
        }
        try {
            return Files.getFileStore(probe).getUsableSpace() / MIB;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static Long positiveMib(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return null;
        }
        long parsed = Long.parseLong(value.strip());
        if (parsed < 1) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return parsed;
    }
}
