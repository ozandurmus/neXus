package com.securityexpert.nexus.ui2.worker.backup.pan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;

import com.securityexpert.nexus.ui2.jobs.transport.*;

import org.junit.jupiter.api.Test;

class PanSetConfigReaderFormatTest {

    private static String brace(int sections) {
        StringBuilder b = new StringBuilder("config {\n");
        for (int i = 0; i < sections; i++) b.append("  section-").append(i).append(" {\n    key value;\n  }\n");
        return b.append("}").toString();
    }

    @Test
    void aCompleteBraceConfigIsAccepted() {
        assertTrue(PanSetConfigReader.isHierarchicalConfig(brace(10)));
    }

    @Test
    void truncatedTinyOrForeignOutputIsRefused() {
        String full = brace(10);
        assertFalse(PanSetConfigReader.isHierarchicalConfig(full.substring(0, full.length() - 1)));
        assertFalse(PanSetConfigReader.isHierarchicalConfig(brace(1)));
        assertFalse(PanSetConfigReader.isHierarchicalConfig("Invalid syntax.\n}"));
    }

    @Test
    void configuredTimeoutIsUsedOnlyForRunningConfig() {
        DeviceTransport transport = mock(DeviceTransport.class);
        TransportSession session = () -> "session-1";
        Duration configured = Duration.ofSeconds(420);
        when(transport.connect(any(), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(new ConnectResult.Authenticated(session));
        when(transport.execInteractive(eq(session), any(), eq(Duration.ofSeconds(20))))
                .thenReturn(new ExecResult.Completed("", 0));
        when(transport.execInteractive(eq(session), eq(new ExecSpec("show config running")), eq(configured)))
                .thenReturn(new ExecResult.Completed(brace(10), 0));

        assertInstanceOf(PanSetConfigReader.Outcome.Read.class, new PanSetConfigReader(transport, configured)
                .read(new ConnectionTarget("endpoint-1", "192.0.2.10", 22), "credential-1", "test-trust"));

        verify(transport, times(3)).execInteractive(eq(session), any(), eq(Duration.ofSeconds(20)));
        verify(transport).execInteractive(session, new ExecSpec("show config running"), configured);
        verify(transport).disconnect(session);
    }

    @Test
    void defaultTimeoutAndTimeoutFailureCloseTheSession() {
        DeviceTransport transport = mock(DeviceTransport.class);
        TransportSession session = () -> "session-1";
        when(transport.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(session));
        when(transport.execInteractive(any(), any(), any())).thenReturn(new ExecResult.Completed("", 0));
        when(transport.execInteractive(eq(session), eq(new ExecSpec("show config running")), any()))
                .thenReturn(new ExecResult.TimedOut());

        assertInstanceOf(PanSetConfigReader.Outcome.Unavailable.class, new PanSetConfigReader(transport)
                .read(new ConnectionTarget("endpoint-1", "192.0.2.10", 22), "credential-1", "test-trust"));

        verify(transport).execInteractive(session, new ExecSpec("show config running"), Duration.ofSeconds(300));
        verify(transport).disconnect(session);
        assertThrows(IllegalArgumentException.class, () -> new PanSetConfigReader(transport, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PanSetConfigReader(transport, Duration.ofDays(30)));
    }
}
