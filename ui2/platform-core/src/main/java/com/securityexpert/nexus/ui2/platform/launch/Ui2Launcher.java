package com.securityexpert.nexus.ui2.platform.launch;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/**
 * The packaged boot jar's one entry point (PO ASSISTANT DECISION
 * 2026-09-14 on packaging, recorded in the movement's baseline; DS-1/DS-2:
 * "the workload role is selected by an explicit typed argument"). {@code
 * args[0]} names the role; every remaining argument passes through
 * unchanged to that role's own {@code main}.
 *
 * <p>Declared in {@code platform-core} (DIR-1: no project dependencies) so
 * this class never imports another role's main class -- each role's main
 * class is resolved by name only, through {@link
 * Class#forName(String, boolean, ClassLoader)} against the <b>thread
 * context class loader</b>. That loader matters: Spring Boot's fat-jar
 * launcher sets it to its own {@code LaunchedClassLoader} before this
 * class's {@code main} runs, and the {@code worker} role's classes reach
 * the boot jar only through {@code service}'s {@code runtimeOnly}
 * classpath (never a compile-time import, so DIR-2 stays intact) --
 * resolving against {@link Class#forName(String)}'s implicit caller-loader
 * overload would miss them (contract risk: "the worker class will not be
 * found at runtime even though it is in BOOT-INF/lib").</p>
 */
public final class Ui2Launcher {

    static final Map<String, String> MAIN_CLASS_BY_ROLE = Map.of(
            "migrate", "com.securityexpert.nexus.ui2.service.boot.MigrationMain",
            "service", "com.securityexpert.nexus.ui2.service.boot.Ui2Application",
            "worker", "com.securityexpert.nexus.ui2.worker.Ui2WorkerMain");

    private Ui2Launcher() {
    }

    public static void main(String[] args) {
        int exitCode = dispatch(MAIN_CLASS_BY_ROLE, args, Thread.currentThread().getContextClassLoader());
        // A successful dispatch never calls System.exit: the role's own main
        // (Spring Boot's embedded server, the worker's claim loop) keeps the
        // JVM alive on its own non-daemon threads once this method returns.
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * @return {@code 0} once the role's {@code main} has been invoked and
     *         returned; {@code 2} for a missing or unknown role (this
     *         method itself never exits the JVM, so a test can call it
     *         directly). A role whose own {@code main} throws is never
     *         caught here -- that exception propagates to the caller
     *         unwrapped, so the reported failure is the role's own, never
     *         a launcher-level wrapper or a {@code ClassNotFoundException}.
     */
    static int dispatch(Map<String, String> mainClassByRole, String[] args, ClassLoader classLoader) {
        if (args.length == 0) {
            System.err.println("ui2_launcher_missing_role: usage: <role> [args...]");
            return 2;
        }
        String role = args[0];
        String mainClassName = mainClassByRole.get(role);
        if (mainClassName == null) {
            System.err.println("ui2_launcher_unknown_role: " + role);
            return 2;
        }
        invokeMain(mainClassName, Arrays.copyOfRange(args, 1, args.length), classLoader);
        return 0;
    }

    private static void invokeMain(String mainClassName, String[] args, ClassLoader classLoader) {
        Method mainMethod;
        try {
            Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
            mainMethod = mainClass.getMethod("main", String[].class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException(
                    "ui2_launcher_role_class_unreachable: main_class=" + mainClassName, e);
        }
        try {
            mainMethod.invoke(null, (Object) args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "ui2_launcher_role_main_not_invocable: main_class=" + mainClassName, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "ui2_launcher_role_main_threw_checked: main_class=" + mainClassName, cause);
        }
    }
}
