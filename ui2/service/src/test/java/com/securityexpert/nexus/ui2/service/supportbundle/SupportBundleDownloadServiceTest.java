package com.securityexpert.nexus.ui2.service.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.StoredItem;
import com.securityexpert.nexus.ui2.platform.TarStream;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

class SupportBundleDownloadServiceTest {
    private static final UUID ID = UUID.fromString("d8f19c2a-4b10-4e8e-9c1a-7b3e2f109a44");
    private final ArtefactStore artefacts = mock(ArtefactStore.class);
    private final JooqSupportBundleStore store = mock(JooqSupportBundleStore.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final TopologyNamePseudonymizer names = mock(TopologyNamePseudonymizer.class);
    private final LogMasking masking = mock(LogMasking.class);
    private final LogMasking.Session session = mock(LogMasking.Session.class);
    private final byte[] hcp = gz("synthetic hcp bytes");

    private static byte[] gz(String s) {
        try {
            var out = new ByteArrayOutputStream();
            try (var g = new GZIPOutputStream(out)) { g.write(s.getBytes(StandardCharsets.UTF_8)); }
            return out.toByteArray();
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private SupportBundleDownloadService service(boolean withStore) throws Exception {
        Instant t = Instant.parse("2026-11-15T01:02:00Z");
        when(store.find(ID)).thenReturn(Optional.of(new RequestRow(ID, "dev-1", "check_point", "cp_gaia_gateway", "NOW", t, "CASE-SECRET",
                "reason with ENV-NAME", "ACTOR-ALPHA", t, t, t, t.plusSeconds(86400), null, null, 0, null, null, "job-1",
                "{\"cpu_max_core_pct\":8}", "COMPLETED", "COMPLETED")));
        when(store.items(ID)).thenReturn(List.of(
                new ItemRow(UUID.randomUUID(), ID, 1, "cp_gaia_extras", null, "COMPLETED", "COMPLETED", null, "items/cp_gaia_extras/",
                        "a1", 100L, "e".repeat(64), "cp-text-v1", List.of("cp_sb_df_var_log"), t, t),
                new ItemRow(UUID.randomUUID(), ID, 2, "cp_hcp_latest", null, "COMPLETED", "COMPLETED", null,
                        "items/cp_hcp_latest/hcp_report.tar.gz", "a2", (long) hcp.length, "f".repeat(64), "none", List.of(), t, t)));
        var inner = TarStream.toBytes(List.of(new TarStream.Entry("items/cp_gaia_extras/df-var-log.txt",
                "host FW-TANGO-04 free 10\n".getBytes(StandardCharsets.UTF_8))), 1);
        byte[] innerGz;
        var o = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(o)) { g.write(inner); }
        innerGz = o.toByteArray();
        when(store.storedItems(ID)).thenReturn(List.of(
                new StoredItem(UUID.randomUUID(), "cp_gaia_extras", "items/cp_gaia_extras/", "a1", "a1.enc", new byte[] {1}, innerGz.length, "e".repeat(64), "COMPLETED", "COMPLETED"),
                new StoredItem(UUID.randomUUID(), "cp_hcp_latest", "items/cp_hcp_latest/hcp_report.tar.gz", "a2", "a2.enc", new byte[] {2}, hcp.length, "f".repeat(64), "COMPLETED", "COMPLETED")));
        when(artefacts.retrieve(any(), any(), anyBoolean())).thenAnswer(call -> {
            Object ref = call.getArgument(0);
            if (ref == null) return null;
            String path = ((com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef) ref).value();
            return new ByteArrayInputStream("a1.enc".equals(path) ? innerGz : hcp);
        });
        var summary = mock(DeviceSummaryRecord.class);
        when(summary.observedHostname()).thenReturn(Optional.of("fwtango04.example.test"));
        when(devices.findSummary("dev-1")).thenReturn(Optional.of(summary));
        when(names.maskDeviceName(anyString(), any(), anyString())).thenReturn("FW-TANGO-04");
        when(masking.session()).thenReturn(session);
        when(session.maskLine(anyString())).thenAnswer(c -> ((String) c.getArgument(0)).replace("FW-TANGO-04", "DEV-MASKED"));
        return new SupportBundleDownloadService(withStore ? artefacts : null, store, devices, names, masking);
    }

    private static List<TarStream.Entry> unpack(SupportBundleDownloadService.Outcome outcome) throws Exception {
        var ready = (SupportBundleDownloadService.Outcome.Ready) outcome;
        var out = new ByteArrayOutputStream();
        ready.body().writeTo(out);
        try (var in = new GZIPInputStream(new ByteArrayInputStream(out.toByteArray()))) { return TarStream.readAll(in, 1 << 20); }
    }

    @Test
    void theAuditRowIsWrittenBeforeAnyCiphertextIsOpened() throws Exception {
        var svc = service(true);
        var outcome = svc.prepare("ACTOR-ALPHA", ID, "Attach to the vendor case", false);
        InOrder order = inOrder(store, artefacts);
        order.verify(store).recordDownload(ID, "ACTOR-ALPHA", "Attach to the vendor case", "raw");
        verify(artefacts, never()).retrieve(any(), any(), anyBoolean());
        var entries = unpack(outcome);
        order.verify(artefacts, org.mockito.Mockito.atLeastOnce()).retrieve(any(), any(), anyBoolean());
        String root = "nexus-support-bundle-d8f19c2a/";
        assertEquals(List.of(root + "manifest.json", root + "items/cp_gaia_extras/df-var-log.txt", root + "items/cp_hcp_latest/hcp_report.tar.gz"),
                entries.stream().map(TarStream.Entry::name).toList());
        assertEquals("host FW-TANGO-04 free 10\n", new String(entries.get(1).bytes(), StandardCharsets.UTF_8));
        assertEquals(new String(hcp, StandardCharsets.ISO_8859_1), new String(entries.get(2).bytes(), StandardCharsets.ISO_8859_1));
    }

    @Test
    void anAuditFailureSendsZeroBytes() throws Exception {
        var svc = service(true);
        doThrow(new IllegalStateException("audit down")).when(store).recordDownload(any(), anyString(), anyString(), anyString());
        var outcome = svc.prepare("ACTOR-ALPHA", ID, "Attach to the vendor case", false);
        assertTrue(outcome instanceof SupportBundleDownloadService.Outcome.AuditRefused);
        verify(artefacts, never()).retrieve(any(), any(), anyBoolean());
    }

    @Test
    void theManifestCarriesThePseudonymAndNoReasonCaseOrActor() throws Exception {
        var entries = unpack(service(true).prepare("ACTOR-ALPHA", ID, "Attach to the vendor case", false));
        String manifest = new String(entries.get(0).bytes(), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"device_pseudonym\" : \"FW-TANGO-04\""), manifest);
        assertTrue(manifest.contains("\"cpu_max_core_pct\" : 8"));
        for (String forbidden : List.of("CASE-SECRET", "reason with ENV-NAME", "ACTOR-ALPHA", "requested_by", "case_ref"))
            assertFalse(manifest.contains(forbidden), forbidden);
    }

    @Test
    void aPseudonymFailureIsUnknownNeverARawName() throws Exception {
        var svc = service(true);
        when(devices.findSummary("dev-1")).thenThrow(new IllegalStateException("cold"));
        assertEquals("UNKNOWN", svc.pseudonym("dev-1"));
    }

    @Test
    void sanitizedModeMasksTextAndLeavesOutBinaryItems() throws Exception {
        var entries = unpack(service(true).prepare("ACTOR-ALPHA", ID, "Attach to the vendor case", true));
        assertEquals(2, entries.size(), "manifest and the one text file");
        assertEquals("host DEV-MASKED free 10\n", new String(entries.get(1).bytes(), StandardCharsets.UTF_8));
        String manifest = new String(entries.get(0).bytes(), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"omitted_in_sanitized_mode\" : true"));
        verify(store).recordDownload(ID, "ACTOR-ALPHA", "Attach to the vendor case", "sanitized");
    }

    /** An ASA or FortiGate capture is a plain text file entry (S4, S5); a Palo Alto tech support file is a binary one (S3). */
    private SupportBundleDownloadService vendorService() throws Exception {
        Instant t = Instant.parse("2026-11-15T01:02:00Z");
        when(store.find(ID)).thenReturn(Optional.of(new RequestRow(ID, "dev-1", "cisco_asa", "cisco_asa_firewall", "NOW", t, null,
                "reason", "ACTOR-ALPHA", t, t, t, t.plusSeconds(86400), null, null, 0, null, null, "job-1", "{}", "COMPLETED", "COMPLETED")));
        byte[] text = "hostname FW-TANGO-04\nip address 192.0.2.10\n".getBytes(StandardCharsets.UTF_8);
        byte[] binary = gz("synthetic tsf");
        when(store.items(ID)).thenReturn(List.of());
        when(store.storedItems(ID)).thenReturn(List.of(
                new StoredItem(UUID.randomUUID(), "asa_show_tech", "items/asa_show_tech/show-tech-support.txt", "a3", "a3.enc", new byte[] {3},
                        text.length, "a".repeat(64), "COMPLETED", "COMPLETED"),
                new StoredItem(UUID.randomUUID(), "pan_tsf", "items/pan_tsf/techsupport.tgz", "a4", "a4.enc", new byte[] {4},
                        binary.length, "b".repeat(64), "COMPLETED", "COMPLETED")));
        when(artefacts.retrieve(any(), any(), anyBoolean())).thenAnswer(call -> {
            Object ref = call.getArgument(0);
            if (ref == null) return null;
            return new ByteArrayInputStream("a3.enc".equals(((com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef) ref).value()) ? text : binary);
        });
        var summary = mock(DeviceSummaryRecord.class);
        when(summary.observedHostname()).thenReturn(Optional.of("fwtango04.example.test"));
        when(devices.findSummary("dev-1")).thenReturn(Optional.of(summary));
        when(names.maskDeviceName(anyString(), any(), anyString())).thenReturn("FW-TANGO-04");
        when(masking.session()).thenReturn(session);
        when(session.maskLine(anyString())).thenAnswer(c -> ((String) c.getArgument(0)).replace("FW-TANGO-04", "DEV-MASKED").replace("192.0.2.10", "10.0.0.1"));
        return new SupportBundleDownloadService(artefacts, store, devices, names, masking);
    }

    @Test
    void aSessionCaptureIsMaskedLineByLineInSanitizedModeAndTheTechSupportFileIsOmitted() throws Exception {
        var raw = unpack(vendorService().prepare("ACTOR-ALPHA", ID, "Attach to the vendor case", false));
        assertEquals(3, raw.size());
        assertEquals("hostname FW-TANGO-04\nip address 192.0.2.10\n", new String(raw.get(1).bytes(), StandardCharsets.UTF_8));
        assertTrue(raw.get(2).name().endsWith("items/pan_tsf/techsupport.tgz"));
        var sanitized = unpack(vendorService().prepare("ACTOR-ALPHA", ID, "Attach to the vendor case", true));
        assertEquals(2, sanitized.size(), "the manifest and the one text capture; the binary file is omitted");
        assertTrue(sanitized.get(1).name().endsWith("items/asa_show_tech/show-tech-support.txt"));
        assertEquals("hostname DEV-MASKED\nip address 10.0.0.1\n", new String(sanitized.get(1).bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aColdMaskingDictionaryRefusesTheSanitizedModeBeforeTheAudit() throws Exception {
        var svc = service(true);
        when(masking.session()).thenThrow(mock(LogMasking.MaskingUnavailableException.class));
        var outcome = svc.prepare("ACTOR-ALPHA", ID, "Attach to the vendor case", true);
        assertTrue(outcome instanceof SupportBundleDownloadService.Outcome.MaskingUnavailable);
        verify(store, never()).recordDownload(any(), anyString(), anyString(), anyString());
    }

    @Test
    void shortReasonMissingStoreAndUnfinishedBundlesAreRefused() throws Exception {
        assertTrue(service(true).prepare("A", ID, "short", false) instanceof SupportBundleDownloadService.Outcome.ReasonTooShort);
        assertTrue(service(false).prepare("A", ID, "Attach to the vendor case", false) instanceof SupportBundleDownloadService.Outcome.StoreUnavailable);
        var svc = service(true);
        when(store.storedItems(ID)).thenReturn(List.of());
        assertTrue(svc.prepare("A", ID, "Attach to the vendor case", false) instanceof SupportBundleDownloadService.Outcome.NothingToDownload);
        when(store.find(ID)).thenReturn(Optional.empty());
        assertTrue(svc.prepare("A", ID, "Attach to the vendor case", false) instanceof SupportBundleDownloadService.Outcome.NotFound);
        verify(store, never()).recordDownload(any(), anyString(), anyString(), anyString());
    }
}
