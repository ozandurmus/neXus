package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository;
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;

class PersistingLifecycleResultSinkTest {

    private record Write(String type, String deviceId, String readKind, String outcome, String reason, int items, Object first) { }

    private static final class FakeRepository implements DeviceLifecycleRepository {
        final List<Write> writes = new ArrayList<>();
        boolean failing;

        @Override
        public void recordLicenseRead(String deviceId, String readKind, String outcome, String reason, List<LicenseItem> items) {
            writes.add(new Write("license", deviceId, readKind, outcome, reason, items.size(), items.isEmpty() ? null : items.get(0)));
            if (failing) {
                throw new IllegalStateException("synthetic " + "jdbc:postgresql://db.example.invalid/x");
            }
        }

        @Override
        public void recordLivePatchRead(String deviceId, String readKind, String outcome, String reason, List<LivePatchItem> items) {
            writes.add(new Write("patch", deviceId, readKind, outcome, reason, items.size(), items.isEmpty() ? null : items.get(0)));
            if (failing) {
                throw new IllegalStateException("synthetic");
            }
        }

        @Override public Map<String, List<Read>> readsByDevice() { return Map.of(); }
        @Override public Map<String, List<LicenseItem>> licenseItemsByDevice() { return Map.of(); }
        @Override public Map<String, List<LivePatchItem>> livePatchByDevice() { return Map.of(); }
    }

    private static final String CPLIC = "Host Expiration Features\n192.0.2.9 never CPSB-FW CK-0123456789AB\n";
    private static final String CPLP = "ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT\nsk1:a armed automatic 1/1 2026-01-01 00:00:00 sk1\n";

    @Test
    void eachParsedKindIsStoredThroughTheRepositoryUnderItsOwnReadKind() {
        FakeRepository repository = new FakeRepository();
        PersistingLifecycleResultSink sink = new PersistingLifecycleResultSink(repository);
        sink.answer(Kind.CP_CPLIC_PRINT, "dev-1", null, CPLIC);
        sink.answer(Kind.CP_SPARK_SHOW_LICENSE, "dev-1", null, CPLIC);
        sink.answer(Kind.CP_CPLP_LIST, "dev-1", null, CPLP);
        sink.answer(Kind.ASA_SHOW_LICENSE_ALL, "dev-1", null, "Compliance status: In compliance\nOverall licensed status: Authorized\n");
        sink.answer(Kind.MC_DEVICE_LICENSE, "dev-1", "SG-A", "{\"components\":[{\"componentName\":\"Proxy\",\"expirationDate\":\"2030-01-01\"}]}");
        sink.answer(Kind.INFOBLOX_LICENSE_GRIDWIDE, "dev-1", null, "[{\"type\":\"DNS\",\"expiry_date\":1798761600}]");
        sink.answer(Kind.PAN_LICENSE_INFO, "dev-1", null, "<response status=\"success\"><result><licenses><entry><feature>PA-VM</feature>"
                + "<expires>Never</expires><expired>no</expired></entry></licenses></result></response>");
        sink.answer(Kind.PAN_SUPPORT_INFO, "dev-1", null, "<response status=\"success\"><result><SupportInfoResponse><Support>"
                + "<ExpiryDate>March 03, 2027</ExpiryDate><SupportLevel>Premium</SupportLevel></Support></SupportInfoResponse></result></response>");
        sink.answer(Kind.FGT_UPDATE_INFO_CONTRACT, "dev-1", null, "System contracts:\nHDWR,Mon May 11 2027\nAccount contracts:\n");
        sink.answer(Kind.FGT_AUTOUPDATE_VERSIONS, "dev-1", null, "AV Engine\n---------\nContract Expiry Date: Mon May 11 2027\nResult: No Updates\n");

        assertEquals(List.of("cp_cplic_print", "cp_spark_show_license", "cp_cplp_list", "asa_show_license_all", "mc_device_license",
                "infoblox_license_gridwide", "pan_license_info", "pan_support_info", "fgt_update_info_contract", "fgt_autoupdate_versions"),
                repository.writes.stream().map(Write::readKind).toList());
        assertTrue(repository.writes.stream().allMatch(w -> w.outcome().equals("COLLECTED") && w.items() == 1 && "dev-1".equals(w.deviceId())),
                repository.writes.toString());
        assertEquals("patch", repository.writes.get(2).type());
        assertTrue(repository.writes.stream().filter(w -> !w.readKind().equals("cp_cplp_list")).allMatch(w -> w.type().equals("license")));
        var mc = (DeviceLifecycleRepository.LicenseItem) repository.writes.get(4).first();
        assertEquals("SG-A", mc.memberRef());
        assertEquals("MANAGER_REPORTED", mc.evidenceGrade());
        assertEquals("grid", ((DeviceLifecycleRepository.LicenseItem) repository.writes.get(5).first()).memberRef());
    }

    @Test
    void theRawAnswerIsNotInAnythingPassedToTheRepository() {
        FakeRepository repository = new FakeRepository();
        new PersistingLifecycleResultSink(repository).answer(Kind.CP_CPLIC_PRINT, "dev-1", null, CPLIC);
        String all = repository.writes.toString();
        assertTrue(!all.contains("192.0.2.9") && !all.contains("CK-0123456789AB"), all);
    }

    @Test
    void blankUnparsedAndForeignAnswersAreRecordedWithTheirOutcomeAndNoItems() {
        FakeRepository repository = new FakeRepository();
        PersistingLifecycleResultSink sink = new PersistingLifecycleResultSink(repository);
        sink.answer(Kind.CP_CPLIC_PRINT, "dev-1", null, "   ");
        sink.answer(Kind.CP_CPLP_LIST, "dev-1", null, "cplp: command not found");
        sink.answer(Kind.PAN_LICENSE_INFO, "dev-1", null, "<html>");
        assertEquals(List.of("EMPTY", "UNPARSED", "UNPARSED"), repository.writes.stream().map(Write::outcome).toList());
        assertEquals(List.of("blank_or_no_items", "no_recognised_line", "no_recognised_line"), repository.writes.stream().map(Write::reason).toList());
        assertTrue(repository.writes.stream().allMatch(w -> w.items() == 0));
    }

    @Test
    void kindsTheContractDoesNotParseAreIgnoredAndSoIsANullDevice() {
        FakeRepository repository = new FakeRepository();
        PersistingLifecycleResultSink sink = new PersistingLifecycleResultSink(repository);
        for (Kind kind : List.of(Kind.MC_SYSTEM_VERSION, Kind.MC_SHOW_LICENSES, Kind.FMG_FMUPDATE_DBCONTRACT, Kind.INFOBLOX_UPGRADESTATUS_GRID)) {
            sink.answer(kind, "dev-1", null, "anything");
            sink.notCollected(kind, "dev-1", "FAILED", "TimedOut");
        }
        sink.answer(Kind.CP_CPLIC_PRINT, null, null, CPLIC);
        sink.answer(Kind.CP_CPLIC_PRINT, " ", null, CPLIC);
        sink.notCollected(Kind.CP_CPLIC_PRINT, null, "FAILED", "TimedOut");
        assertTrue(repository.writes.isEmpty(), repository.writes.toString());
    }

    @Test
    void notCollectedOutcomesAreRecordedWithACleanReasonAndNoItems() {
        FakeRepository repository = new FakeRepository();
        PersistingLifecycleResultSink sink = new PersistingLifecycleResultSink(repository);
        sink.notCollected(Kind.CP_CPLIC_PRINT, "dev-1", "SKIPPED", "management_server");
        sink.notCollected(Kind.CP_CPLP_LIST, "dev-1", "FAILED", "Time out!");
        sink.notCollected(Kind.ASA_SHOW_LICENSE_ALL, "dev-1", "REFUSED", "CliRefusal");
        sink.notCollected(Kind.PAN_LICENSE_INFO, "dev-1", "WHATEVER", "x");
        sink.notCollected(Kind.FGT_UPDATE_INFO_CONTRACT, "dev-1", "FAILED", null);
        assertEquals(4, repository.writes.size(), "an outcome outside the closed set is dropped");
        assertEquals(new Write("license", "dev-1", "cp_cplic_print", "SKIPPED", "management_server", 0, null), repository.writes.get(0));
        assertEquals(new Write("patch", "dev-1", "cp_cplp_list", "FAILED", "Time_out_", 0, null), repository.writes.get(1));
        assertEquals("REFUSED", repository.writes.get(2).outcome());
        assertNull(repository.writes.get(3).reason());
    }

    @Test
    void aFailingRepositoryNeverEscapesAndTheGuardedSinkNeverThrows() {
        FakeRepository repository = new FakeRepository();
        repository.failing = true;
        PersistingLifecycleResultSink sink = new PersistingLifecycleResultSink(repository);
        assertDoesNotThrow(() -> sink.answer(Kind.CP_CPLIC_PRINT, "dev-1", null, CPLIC));
        assertDoesNotThrow(() -> sink.answer(Kind.CP_CPLP_LIST, "dev-1", null, CPLP));
        assertDoesNotThrow(() -> sink.notCollected(Kind.CP_CPLIC_PRINT, "dev-1", "FAILED", "TimedOut"));

        RecordingLifecycleSink throwing = RecordingLifecycleSink.throwing();
        LifecycleResultSink guarded = LifecycleResultSink.guarded(throwing);
        assertDoesNotThrow(() -> guarded.answer(Kind.CP_CPLIC_PRINT, "dev-1", null, "x"));
        assertDoesNotThrow(() -> guarded.notCollected(Kind.CP_CPLIC_PRINT, "dev-1", "FAILED", "x"));
        assertEquals(2, throwing.calls.size());
        assertTrue(LifecycleResultSink.guarded(guarded) == guarded, "wrapping twice does not nest");
    }
}
