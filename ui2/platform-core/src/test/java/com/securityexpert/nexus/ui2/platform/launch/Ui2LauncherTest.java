package com.securityexpert.nexus.ui2.platform.launch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Dispatch-only tests against a stub role table -- never the real {@code
 * service}/{@code worker} main classes, which {@code platform-core} cannot
 * even compile against (DIR-1). {@link Ui2Launcher#dispatch} is the
 * package-visible seam this suite exercises directly, so a test never
 * calls {@link System#exit(int)} the way {@link Ui2Launcher#main} does.
 */
class Ui2LauncherTest {

    public static final class StubRoleMain {
        static final AtomicReference<String[]> lastArgs = new AtomicReference<>();

        public static void main(String[] args) {
            lastArgs.set(args);
        }
    }

    public static final class ThrowingRoleMain {
        public static void main(String[] args) {
            throw new IllegalStateException("stub_role_failure");
        }
    }

    private static final Map<String, String> ROLE_TABLE = Map.of(
            "stub-role", StubRoleMain.class.getName(),
            "throwing-role", ThrowingRoleMain.class.getName());

    @Test
    void knownRoleDispatchesToItsMainClassWithRemainingArgs() {
        int exitCode = Ui2Launcher.dispatch(ROLE_TABLE, new String[] {"stub-role", "a", "b"}, getClass().getClassLoader());

        assertEquals(0, exitCode);
        assertArrayEquals(new String[] {"a", "b"}, StubRoleMain.lastArgs.get());
    }

    @Test
    void missingRoleFailsClosedWithExitCodeTwo() {
        int exitCode = Ui2Launcher.dispatch(ROLE_TABLE, new String[0], getClass().getClassLoader());

        assertEquals(2, exitCode);
    }

    @Test
    void unknownRoleFailsClosedWithExitCodeTwo() {
        int exitCode = Ui2Launcher.dispatch(ROLE_TABLE, new String[] {"nonexistent"}, getClass().getClassLoader());

        assertEquals(2, exitCode);
    }

    @Test
    void aRoleMainsOwnExceptionPropagatesUnwrapped() {
        // AC-3 / WORKER.md risk: the reported failure must be the role's
        // own -- never a launcher-level wrapper, and never a
        // ClassNotFoundException hiding it.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Ui2Launcher.dispatch(ROLE_TABLE, new String[] {"throwing-role"}, getClass().getClassLoader()));

        assertEquals("stub_role_failure", thrown.getMessage());
    }

    @Test
    void productionRoleTableNamesExactlyServiceAndWorker() {
        assertEquals(Map.of(
                "service", "com.securityexpert.nexus.ui2.service.boot.Ui2Application",
                "worker", "com.securityexpert.nexus.ui2.worker.Ui2WorkerMain"),
                Ui2Launcher.MAIN_CLASS_BY_ROLE);
    }
}
