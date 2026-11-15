package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.StoredItem;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore.UploadRow;
import com.securityexpert.nexus.ui2.service.supportbundle.SupportBundleDownloadService;

class SupportBundleUploadServiceTest {

    private static final UUID REQUEST = UUID.fromString("d8f19c2a-4b10-4e8e-9c1a-7b3e2f109a44");
    private static final String SECRET = "synthetic-secret-token-9";
    private static final byte[] ARCHIVE = "synthetic archive bytes, not a real bundle".getBytes(StandardCharsets.UTF_8);

    private final SupportBundleDownloadService downloads = mock(SupportBundleDownloadService.class);
    private final JooqSupportBundleStore bundles = mock(JooqSupportBundleStore.class);
    private final JooqSupportBundleUploadStore uploads = mock(JooqSupportBundleUploadStore.class);
    private final CountDownLatch done = new CountDownLatch(1);

    private static RequestRow request(String vendor) {
        return new RequestRow(REQUEST, "dev-1", vendor, "gateway", "NOW", Instant.now(), "CASE-1", "reason text", "actor-1",
                Instant.now(), Instant.now(), Instant.now(), Instant.now().plusSeconds(3600), null, null, 0, null, null, "job-1", "{}",
                "COMPLETED", "COMPLETED");
    }

    private void plan(UploadBodyWriter writer) {
        StoredItem item = new StoredItem(UUID.randomUUID(), "asa_show_tech", "items/x", "art-1", "path", new byte[0], 10, null, "COMPLETED", "COMPLETED");
        when(downloads.uploadPlan(eq(REQUEST), any())).thenReturn(Optional.of(new SupportBundleDownloadService.UploadPlan(
                "nexus-support-bundle-cisco_asa-d8f19c2a-20261116-0100.tgz", List.of(item), Set.of(item.itemId()),
                out -> writer.write(out))));
    }

    private interface UploadBodyWriter { void write(OutputStream out) throws IOException; }

    private SupportBundleUploadService service(UploadTransfer transfer, AtomicInteger sleeps) {
        when(bundles.find(REQUEST)).thenReturn(Optional.of(request("cisco_asa")));
        org.mockito.Mockito.doAnswer(i -> { done.countDown(); return true; }).when(uploads)
                .finish(any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt());
        return new SupportBundleUploadService(downloads, bundles, uploads, Map.of(
                UploadDestination.Method.HTTPS_PUT, transfer, UploadDestination.Method.SFTP, transfer, UploadDestination.Method.SCP, transfer),
                d -> sleeps.incrementAndGet());
    }

    private static SupportBundleUploadService.Spec spec(String destination, char[] password) {
        return new SupportBundleUploadService.Spec(destination, null, null, "CASE-1", password, List.of(), "CASE-1", "Attach to the TAC case", true);
    }

    private static void drain(UploadBody body, OutputStream out) throws UploadException {
        try {
            body.writeTo(out);
        } catch (IOException e) {
            throw new UploadException("WRITE_FAILED", "test", false);
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void aSuccessfulUploadRecordsBytesDigestAndStatusAndNeverTheCredential() throws Exception {
        plan(out -> out.write(ARCHIVE));
        List<char[]> seenPassword = new ArrayList<>();
        AtomicInteger sleeps = new AtomicInteger();
        SupportBundleUploadService service = service((target, password, body, keys) -> {
            seenPassword.add(password);
            assertEquals(SECRET, new String(password));
            assertEquals(ARCHIVE.length, body.length());
            drain(body, OutputStream.nullOutputStream());
            return new TransferResult("HTTP 201", null, false);
        }, sleeps);
        char[] password = SECRET.toCharArray();
        var outcome = service.start(REQUEST, "actor-1", spec("cisco_cxd_https", password));
        assertInstanceOf(SupportBundleUploadService.Outcome.Started.class, outcome);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sha = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> bytes = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(uploads).finish(any(), eq("actor-1"), state.capture(), bytes.capture(), any(), sha.capture(), status.capture(), any(), any(), any(),
                anyBoolean(), eq(1));
        assertEquals("COMPLETED", state.getValue());
        assertEquals((long) ARCHIVE.length, bytes.getValue());
        assertEquals(sha(ARCHIVE), sha.getValue());
        assertEquals("HTTP 201", status.getValue());
        // the start row names the destination and the account, and carries no credential
        ArgumentCaptor<UploadRow> row = ArgumentCaptor.forClass(UploadRow.class);
        verify(uploads).start(row.capture());
        assertEquals("cisco_cxd_https", row.getValue().destinationKind());
        assertEquals("cxd.cisco.com", row.getValue().destinationHost());
        assertEquals(443, row.getValue().destinationPort());
        assertFalse(row.getValue().toString().contains(SECRET));
        // the array handed to the job was wiped afterwards
        assertTrue(done.getCount() == 0);
        Thread.sleep(100);
        for (char[] p : seenPassword) for (char c : p) assertEquals('\0', c);
        for (char c : password) assertEquals('\0', c);
    }

    @Test
    void aConnectionThatFailedBeforeAnyByteIsRetriedExactlyOnce() throws Exception {
        plan(out -> out.write(ARCHIVE));
        AtomicInteger calls = new AtomicInteger(), sleeps = new AtomicInteger();
        SupportBundleUploadService service = service((t, p, body, k) -> {
            if (calls.incrementAndGet() == 1) throw new UploadException("CONNECTION_FAILED", "Could not reach x", true);
            drain(body, OutputStream.nullOutputStream());
            return new TransferResult("SFTP_OK", null, false);
        }, sleeps);
        service.start(REQUEST, "actor-1", spec("cisco_cxd_sftp", SECRET.toCharArray()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        assertEquals(1, sleeps.get());
        verify(uploads).finish(any(), any(), eq("COMPLETED"), anyLong(), any(), any(), any(), any(), any(), any(), anyBoolean(), eq(2));
    }

    @Test
    void aSecondFailureIsFinalAndItsDetailKeepsTheFirewallHint() throws Exception {
        plan(out -> out.write(ARCHIVE));
        AtomicInteger calls = new AtomicInteger();
        SupportBundleUploadService service = service((t, p, body, k) -> {
            calls.incrementAndGet();
            throw new UploadException("CONNECTION_FAILED", "Could not reach h:22. The bank firewall may need an opening.", true);
        }, new AtomicInteger());
        service.start(REQUEST, "actor-1", spec("cisco_cxd_sftp", SECRET.toCharArray()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(uploads).finish(any(), any(), eq("FAILED"), anyLong(), any(), any(), any(), eq("CONNECTION_FAILED"), detail.capture(), any(),
                anyBoolean(), eq(2));
        assertTrue(detail.getValue().contains("firewall"));
    }

    @Test
    void anAuthenticationRefusalIsNeverRetried() throws Exception {
        plan(out -> out.write(ARCHIVE));
        AtomicInteger calls = new AtomicInteger();
        SupportBundleUploadService service = service((t, p, body, k) -> { calls.incrementAndGet(); throw new UploadException("AUTH_FAILED", "refused", false); }, new AtomicInteger());
        service.start(REQUEST, "actor-1", spec("cisco_cxd_scp", SECRET.toCharArray()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void noRetryOnceBytesWereSent() throws Exception {
        plan(out -> out.write(ARCHIVE));
        AtomicInteger calls = new AtomicInteger();
        SupportBundleUploadService service = service((t, p, body, k) -> {
            calls.incrementAndGet();
            OutputStream out = new OutputStream() { @Override public void write(int b) { } };
            drain(body, out);
            throw new UploadException("CONNECTION_FAILED", "lost after sending", true);
        }, new AtomicInteger());
        service.start(REQUEST, "actor-1", spec("cisco_cxd_sftp", SECRET.toCharArray()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void anArchiveThatChangesBetweenThePassesIsNotReportedAsSent() throws Exception {
        AtomicInteger pass = new AtomicInteger();
        plan(out -> out.write(pass.incrementAndGet() == 1 ? ARCHIVE : "synthetic archive bytes, XXX a real bundle".getBytes(StandardCharsets.UTF_8)));
        SupportBundleUploadService service = service((t, p, body, k) -> {
            drain(body, OutputStream.nullOutputStream());
            return new TransferResult("HTTP 201", null, false);
        }, new AtomicInteger());
        service.start(REQUEST, "actor-1", spec("cisco_cxd_https", SECRET.toCharArray()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        verify(uploads).finish(any(), any(), eq("FAILED"), anyLong(), any(), any(), any(), eq("DIGEST_CHANGED"), any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void aVaultReadFailureIsASourceFailureAndNothingIsSent() throws Exception {
        plan(out -> { throw new IOException("vault gone"); });
        AtomicInteger calls = new AtomicInteger();
        SupportBundleUploadService service = service((t, p, body, k) -> { calls.incrementAndGet(); return new TransferResult("x", null, false); }, new AtomicInteger());
        service.start(REQUEST, "actor-1", spec("cisco_cxd_https", SECRET.toCharArray()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, calls.get());
        verify(uploads).finish(any(), any(), eq("FAILED"), anyLong(), any(), any(), any(), eq("SOURCE_FAILED"), any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void theDestinationMustMatchTheBundleVendorAndTheGenericServerIsValidated() {
        plan(out -> out.write(ARCHIVE));
        SupportBundleUploadService service = service((t, p, b, k) -> new TransferResult("x", null, false), new AtomicInteger());
        var mismatch = (SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "actor-1", spec("fortinet_sftp", SECRET.toCharArray()));
        assertEquals("DESTINATION_VENDOR_MISMATCH", mismatch.code());
        for (String host : new String[] {null, "", "bad host", "host/with/path", "user@host", "sftp://host", "-oProxyCommand=x", "a..b", "host:22"}) {
            var spec = new SupportBundleUploadService.Spec("generic_sftp", host, 22, "u", "p".toCharArray(), List.of(), null, "Attach to the case", true);
            var refused = (SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "actor-1", spec);
            assertEquals("INVALID_HOST", refused.code(), String.valueOf(host));
        }
        for (int port : new int[] {0, -1, 65536, 99999}) {
            var spec = new SupportBundleUploadService.Spec("generic_sftp", "sftp.example.com", port, "u", "p".toCharArray(), List.of(), null, "Attach to the case", true);
            assertEquals("INVALID_PORT", ((SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "actor-1", spec)).code());
        }
        var fixedHost = new SupportBundleUploadService.Spec("cisco_cxd_https", "evil.example.com", null, "u", "p".toCharArray(), List.of(), null, "Attach to the case", true);
        assertEquals("DESTINATION_FIXED", ((SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "actor-1", fixedHost)).code());
        verify(uploads, never()).start(any());
    }

    @Test
    void refusalsHappenBeforeAnythingIsWrittenAndTheSecretIsWiped() {
        plan(out -> out.write(ARCHIVE));
        SupportBundleUploadService service = service((t, p, b, k) -> new TransferResult("x", null, false), new AtomicInteger());
        char[] secret = SECRET.toCharArray();
        var unconfirmed = new SupportBundleUploadService.Spec("cisco_cxd_https", null, null, "CASE-1", secret, List.of(), null, "Attach to the case", false);
        assertEquals("CONFIRMATION_REQUIRED", ((SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "a", unconfirmed)).code());
        var shortReason = new SupportBundleUploadService.Spec("cisco_cxd_https", null, null, "CASE-1", SECRET.toCharArray(), List.of(), null, "short", true);
        assertEquals("REASON_REQUIRED", ((SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "a", shortReason)).code());
        var noPassword = new SupportBundleUploadService.Spec("cisco_cxd_https", null, null, "CASE-1", new char[0], List.of(), null, "Attach to the case", true);
        assertEquals("PASSWORD_REQUIRED", ((SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "a", noPassword)).code());
        var badUser = new SupportBundleUploadService.Spec("cisco_cxd_https", null, null, "a b;c", SECRET.toCharArray(), List.of(), null, "Attach to the case", true);
        assertEquals("INVALID_USER", ((SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "a", badUser)).code());
        var badDestination = new SupportBundleUploadService.Spec("checkpoint_uploader", null, null, "u", SECRET.toCharArray(), List.of(), null, "Attach to the case", true);
        assertEquals("INVALID_DESTINATION", ((SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "a", badDestination)).code());
        verify(uploads, never()).start(any());
    }

    @Test
    void aSecondUploadOfTheSameBundleIsRefusedWhileOneRuns() {
        plan(out -> out.write(ARCHIVE));
        SupportBundleUploadService service = service((t, p, b, k) -> new TransferResult("x", null, false), new AtomicInteger());
        org.mockito.Mockito.doThrow(new RuntimeException("duplicate key value violates unique constraint \"uq_support_bundle_upload_running\""))
                .when(uploads).start(any());
        var refused = (SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "actor-1", spec("cisco_cxd_https", SECRET.toCharArray()));
        assertEquals(409, refused.status());
        assertEquals("UPLOAD_ALREADY_RUNNING", refused.code());
    }

    @Test
    void anAuditFailureSendsNothing() {
        plan(out -> out.write(ARCHIVE));
        SupportBundleUploadService service = service((t, p, b, k) -> { throw new AssertionError("must not run"); }, new AtomicInteger());
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(uploads).start(any());
        var refused = (SupportBundleUploadService.Outcome.Refused) service.start(REQUEST, "actor-1", spec("cisco_cxd_https", SECRET.toCharArray()));
        assertEquals("AUDIT_REFUSED", refused.code());
    }
}
