package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed;
import com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogTemplateCensus.Options;

/**
 * Entry point of the system-log template census, run in the pod as a second JVM on the boot jar (the worker classes
 * are packed into it):
 *
 * <pre>
 * java -Xmx3g -Djava.io.tmpdir=/app/tmp -Duser.home=/app/home -cp /app/service.jar \
 *   -Dloader.main=com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogTemplateCensusMain \
 *   org.springframework.boot.loader.launch.PropertiesLauncher \
 *   --from 2026-10-01 --to 2026-11-23 --sources cp_messages,pan_system --out /app/tmp/census.json
 * </pre>
 *
 * The decrypting, masking feed is the service's ({@link SystemLogCensusFeed.Provider}, loaded by class name because
 * the two modules may not depend on each other); this class owns the arguments, the output and the exit code. Standard
 * error carries progress lines and one-word failure labels only (lowercase exception class names), never a message.
 * Exit codes: 0 report written, 2 bad arguments, 3 run failed (including the output guard; nothing is written).
 */
public final class SystemLogTemplateCensusMain {
    static final String PROVIDER_CLASS = "com.securityexpert.nexus.ui2.service.systemlog.SystemLogCensusFeedProvider";
    static final String OUT_ROOT = "/app/tmp";

    private SystemLogTemplateCensusMain() {
    }

    public static void main(String[] args) {
        PrintStream stdout = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream progress = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.setErr(new PrintStream(OutputStream.nullOutputStream())); // library messages could carry a value
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        silenceLogging();
        SystemLogCensusFeed.Provider provider;
        try {
            provider = (SystemLogCensusFeed.Provider) Class.forName(PROVIDER_CLASS).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            progress.println("fatal feed_provider_unavailable");
            progress.flush();
            System.exit(3);
            return;
        }
        int exit = run(args, provider, stdout, progress, Path.of(OUT_ROOT));
        stdout.flush();
        progress.flush();
        System.exit(exit);
    }

    /** The whole run, with its collaborators passed in; returns the exit code. */
    static int run(String[] args, SystemLogCensusFeed.Provider provider, PrintStream stdout, PrintStream progress, Path outRoot) {
        Options options;
        Path target;
        try {
            options = Options.parse(args);
            target = options.out() == null ? null : outPath(options.out(), outRoot);
        } catch (IllegalArgumentException e) {
            progress.println("usage_error");
            return 2;
        }
        try (SystemLogCensusFeed feed = provider.open(CensusMaskGuard::leak, progress)) {
            String json = SystemLogTemplateCensus.run(feed, options, progress);
            if (target == null) {
                stdout.println(json);
            } else {
                Path tmp = target.resolveSibling(target.getFileName() + ".part");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                progress.println("wrote report bytes=" + json.length());
            }
            return 0;
        } catch (Throwable t) {
            progress.println("fatal " + (t instanceof IllegalStateException && String.valueOf(t.getMessage()).startsWith("census output guard")
                    ? "output_guard_failed" : t.getClass().getSimpleName().toLowerCase(Locale.ROOT)));
            return 3;
        }
    }

    /** {@code --out} must name a file directly under the output root (the pod's tmp directory). */
    static Path outPath(String out, Path root) {
        Path base = root.toAbsolutePath().normalize();
        Path p = Path.of(out).toAbsolutePath().normalize();
        if (p.getParent() == null || !p.getParent().equals(base)) {
            throw new IllegalArgumentException("--out must be a file directly under the output root");
        }
        return p;
    }

    static void silenceLogging() {
        try {
            java.util.logging.LogManager.getLogManager().reset();
            java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            Object root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Class<?> level = Class.forName("ch.qos.logback.classic.Level");
            root.getClass().getMethod("setLevel", level).invoke(root, level.getField("OFF").get(null));
        } catch (Throwable ignored) {
            // best effort
        }
        System.setProperty("org.jooq.no-logo", "true");
        System.setProperty("org.jooq.no-tips", "true");
    }
}
