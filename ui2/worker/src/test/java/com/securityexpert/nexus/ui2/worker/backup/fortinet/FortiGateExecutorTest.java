package com.securityexpert.nexus.ui2.worker.backup.fortinet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.transcript.TranscriptAssertions;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

class FortiGateExecutorTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transcriptNamesStoredConfigurationOrRefusal(boolean refused, @TempDir Path dir) {
        var transport = mock(DeviceTransport.class);
        TransportSession session = () -> "synthetic-session";
        when(transport.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(session));
        when(transport.execInteractive(eq(session), any(), any())).thenAnswer(call -> {
            ExecSpec command = call.getArgument(1);
            String text = command.command().equals(FortiGatePlan.SHOW)
                    ? (refused ? "Permission denied" : "#config-version=synthetic\nconfig system global\nend\n")
                    : "Version: FortiGate synthetic\n";
            return new ExecResult.Completed(text, 0);
        });
        var store = new FileArtefactStore(dir, ArtefactStoreCipher.fromBase64Key(
                Base64.getEncoder().encodeToString(new byte[32])));
        var executor = new FortiGateExecutor(transport, store);
        BackupResult result = TranscriptAssertions.capture(
                () -> executor.backup(new Target("192.0.2.10", 22), "credential", "device", "job"),
                "precheck started", refused ? "no FortiOS configuration" : "FortiOS configuration header verified",
                refused ? "SubmitOutputUnparseable" : "sha256=");
        if (refused) assertInstanceOf(BackupResult.SubmitOutputUnparseable.class, result);
        else assertInstanceOf(BackupResult.Completed.class, result);
        verify(transport).disconnect(session);
    }
}
