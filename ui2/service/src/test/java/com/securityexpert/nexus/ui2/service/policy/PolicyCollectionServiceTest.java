package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.Result;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;

class PolicyCollectionServiceTest {
    @SuppressWarnings("unchecked")
    private static Result<Record> result(Record row) {
        Result<Record> result = mock(Result.class);
        when(result.stream()).thenAnswer(call -> row == null ? Stream.empty() : Stream.of(row));
        when(result.map(any(RecordMapper.class))).thenAnswer(call -> row == null ? List.of()
            : List.of(((RecordMapper<Record, ?>) call.getArgument(0)).map(row)));
        return result;
    }
    @SuppressWarnings("unchecked")
    @Test void sourceNamesReuseDeviceSummaryAndExistingAIViewMaskingWithoutUuidLabels() {
        var tx = mock(TransactionBoundary.class);
        var db = mock(DSLContext.class);
        when(tx.inTransaction(any())).thenAnswer(call -> ((Function<DSLContext, ?>) call.getArgument(0)).apply(db));
        var source = mock(Record.class);
        when(source.get("device_id", String.class)).thenReturn("manager-1");
        when(source.get("run_id", String.class)).thenReturn("run-1");
        when(source.get("vendor_hint", String.class)).thenReturn("palo_alto");
        var device = mock(Record.class);
        when(device.get("device_id", String.class)).thenReturn("manager-1");
        when(device.get("enrollment_state", String.class)).thenReturn("ENROLLED");
        when(device.get("observed_hostname", String.class)).thenReturn("MGR-BRAVO-01");
        when(db.fetch(startsWith("select d.device_id, r.run_id"))).thenReturn(result(source));
        when(db.fetch(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            return result(sql.startsWith("select d.device_id, d.role") ? device : null);
        });
        var service = new PolicyCollectionService(tx);
        var view = service.sources().get(0);
        assertEquals("MGR-BRAVO-01", view.get("sourceName"));
        assertEquals("manager-1", view.get("sourceId"));
        var names = new TopologyNamePseudonymizer(new byte[32]);
        var masked = (Map<?, ?>) PolicyPrivacy.mask(view, "", names, new SubnetPreservingIpMasker(new byte[32]));
        assertEquals(names.maskDeviceName("MGR-BRAVO-01", null), masked.get("sourceName"));
        when(device.get("observed_hostname", String.class)).thenReturn(null);
        assertEquals("Management server", service.sources().get(0).get("sourceName"));
    }
    @SuppressWarnings("unchecked")
    @Test void presentationAddsOnlyExistingFinishTimeAndTranscriptAvailability() {
        var tx = mock(TransactionBoundary.class);
        var db = mock(DSLContext.class);
        when(tx.inTransaction(any())).thenAnswer(call -> ((Function<DSLContext, ?>) call.getArgument(0)).apply(db));
        var progress = mock(Record.class);
        when(progress.get("state", String.class)).thenReturn("FAILED");
        when(progress.get("reason", String.class)).thenReturn("TIMEOUT");
        when(progress.get("step", Integer.class)).thenReturn(6);
        when(progress.get("total", Integer.class)).thenReturn(60);
        when(progress.get("layer", Integer.class)).thenReturn(2);
        when(progress.get("layers", Integer.class)).thenReturn(5);
        when(progress.get("rules", Integer.class)).thenReturn(4000);
        var presentation = mock(Record.class);
        when(presentation.get("finished_at", OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-10-02T04:00:00Z"));
        when(presentation.get("has_transcript", Boolean.class)).thenReturn(true);
        when(db.fetch(anyString(), any(Object[].class))).thenAnswer(call -> result(
            ((String) call.getArgument(0)).startsWith("select finished_at") ? presentation : progress));
        var view = new PolicyCollectionService(tx).status("job-1").orElseThrow();
        assertEquals("2026-10-02T04:00:00Z", view.get("collectedAt"));
        assertEquals(true, view.get("hasTranscript"));
        assertEquals(6, view.get("step"));
        assertEquals(60, view.get("total"));
        assertEquals(2, view.get("layer"));
        assertEquals(5, view.get("layers"));
        assertEquals(4000, view.get("rulesFetched"));
        var masked = (Map<?, ?>) PolicyPrivacy.mask(view, "", new TopologyNamePseudonymizer(new byte[32]), new SubnetPreservingIpMasker(new byte[32]));
        assertEquals(view.get("collectedAt"), masked.get("collectedAt"));
        assertEquals(true, masked.get("hasTranscript"));
    }
}
