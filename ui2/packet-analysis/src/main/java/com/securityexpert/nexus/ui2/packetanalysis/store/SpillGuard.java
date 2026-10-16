package com.securityexpert.nexus.ui2.packetanalysis.store;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The hard bound on spill. DuckDB's {@code max_temp_directory_size} is a soft bound: measured with DuckDB 1.5.6 it is
 * exceeded several times over (a 256 MiB setting reached 1 GiB in a sort; a real case with a 40 GiB setting wrote
 * 188 GiB). On a shared analysis volume that would fill the claim for every user, so a watchdog polls the size of the
 * case's spill directory and the free space of its volume while a run is in progress and, past the hard limit,
 * <em>trips</em>: the run's open DuckDB connections are interrupted, and the run ends as a memory-limit failure
 * ({@link AnalysisFailure#MEMORY_LIMIT}), never as a full volume.
 *
 * <p>Only the spill directory of the running case is watched; the case database and the captures are not touched.
 * A trip is sticky until the next {@link #run} so the failure is classified even after the connections are gone.
 */
public final class SpillGuard implements AutoCloseable {

    /** System property: the hard spill limit in MiB; default twice the DuckDB spill ceiling ({@link MemoryPlan}). */
    public static final String HARD_TEMP_PROPERTY = "packetanalysis.duckdb.hard_temp_mb";
    /** System property: the poll interval in milliseconds (default 1000). */
    public static final String POLL_PROPERTY = "packetanalysis.spill.poll_ms";

    private static final Set<Connection> CONNECTIONS = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<>()));
    private static volatile String trippedBy;
    private static final System.Logger LOG = System.getLogger(SpillGuard.class.getName());

    private final Path spill;
    private final long hardMib;
    private final long pollMillis;
    private final Thread thread;
    private volatile boolean stopped;

    private SpillGuard(Path spill, long hardMib, long pollMillis) {
        this.spill = spill;
        this.hardMib = hardMib;
        this.pollMillis = pollMillis;
        this.thread = new Thread(this::poll, "packet-analysis-spill-guard");
        this.thread.setDaemon(true);
    }

    /** Registers a connection of the run, so a trip can interrupt it. Returns the connection. */
    public static <C extends Connection> C track(C connection) {
        CONNECTIONS.add(connection);
        return connection;
    }

    /** True from a trip until the next {@link #run}. */
    public static boolean tripped() {
        return trippedBy != null;
    }

    /** The hard limit in MiB: the property, else twice the DuckDB spill ceiling, lowered to the volume's free space. */
    public static long hardLimitMib(Path spill) {
        String configured = System.getProperty(HARD_TEMP_PROPERTY);
        long limit = configured != null && !configured.isBlank() ? Long.parseLong(configured.strip())
                : 2 * MemoryPlan.maxTempMib(spill);
        return Math.max(MemoryPlan.maxTempMib(spill), limit);
    }

    /**
     * Runs {@code body} with the spill of {@code caseDir} watched. If the guard trips, the run fails with
     * {@link AnalysisFailure#MEMORY_LIMIT} whether the body threw or returned.
     */
    public static <T> T run(Path caseDir, Supplier<T> body) {
        Path spill = MemoryPlan.spillDirectory(caseDir);
        trippedBy = null;
        long poll = Long.getLong(POLL_PROPERTY, 1000L);
        try (SpillGuard guard = new SpillGuard(spill, hardLimitMib(spill), poll)) {
            guard.thread.start();
            T result;
            try {
                result = body.get();
            } catch (RuntimeException | OutOfMemoryError failure) {
                if (tripped() && !(failure instanceof AnalysisLimitException)) {
                    throw limit(failure);
                }
                throw failure;
            }
            if (tripped()) {
                throw limit(null);
            }
            return result;
        }
    }

    private static AnalysisLimitException limit(Throwable cause) {
        return new AnalysisLimitException(AnalysisFailure.MEMORY_LIMIT, AnalysisFailure.MEMORY_LIMIT_MESSAGE, cause);
    }

    @Override
    public void close() {
        stopped = true;
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void poll() {
        while (!stopped) {
            try {
                long usedMib = sizeMib(spill);
                long usable = usableMib(spill);
                if (usedMib > hardMib) {
                    trip("spill " + usedMib + " MiB > hard limit " + hardMib + " MiB");
                } else if (usable >= 0 && usable < MemoryPlan.FREE_SPACE_GUARD_MIB && usedMib > 0) {
                    trip("volume has " + usable + " MiB free");
                }
                if (tripped()) {
                    interruptAll();
                }
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // A watchdog that fails must not fail the run; the next poll tries again.
            }
        }
    }

    private static void trip(String why) {
        if (trippedBy == null) {
            trippedBy = why;
            LOG.log(System.Logger.Level.WARNING, "[PACKET_ANALYSIS] spill guard tripped: {0}", why);
        }
    }

    private static void interruptAll() {
        List<Connection> open;
        synchronized (CONNECTIONS) {
            open = new ArrayList<>(CONNECTIONS);
        }
        for (Connection c : open) {
            try {
                if (!c.isClosed()) {
                    interrupt(c);
                }
            } catch (Exception e) {
                // closed or not interruptible: the next poll tries the others
                LOG.log(System.Logger.Level.DEBUG, "[PACKET_ANALYSIS] interrupt failed: {0}", e.toString());
            }
        }
    }

    /**
     * DuckDB's {@code DuckDBConnection.interrupt()}, the call behind {@code Statement.cancel()}. The driver keeps it
     * package-private and requires the connection's own lock to be held (a running statement does not hold it during
     * native execution), so it is called under that lock with a bounded wait.
     */
    private static void interrupt(Connection c) throws Exception {
        java.lang.reflect.Field field = c.getClass().getDeclaredField("connRefLock");
        field.setAccessible(true);
        java.util.concurrent.locks.ReentrantLock lock = (java.util.concurrent.locks.ReentrantLock) field.get(c);
        Method m = c.getClass().getDeclaredMethod("interrupt");
        m.setAccessible(true);
        if (!lock.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return;
        }
        try {
            m.invoke(c);
        } finally {
            lock.unlock();
        }
    }

    /** Sum of the sizes of the files directly in {@code dir} (DuckDB's temp files are flat), MiB. */
    static long sizeMib(Path dir) {
        long bytes = 0;
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                try {
                    if (Files.isRegularFile(f)) {
                        bytes += Files.size(f);
                    }
                } catch (IOException e) {
                    // removed while listing
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return bytes / MemoryPlan.MIB;
    }

    private static long usableMib(Path spill) {
        Path probe = spill;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        try {
            return probe == null ? -1 : Files.getFileStore(probe).getUsableSpace() / MemoryPlan.MIB;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
