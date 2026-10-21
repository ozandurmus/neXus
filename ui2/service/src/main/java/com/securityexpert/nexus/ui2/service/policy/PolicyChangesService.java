package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;

@Service
public final class PolicyChangesService {
    public static final String UNKNOWN_ACTOR = "UNKNOWN (observed by collection)";
    public static final Set<String> FIELDS = Set.of("name", "number", "enabled", "source", "destination", "service", "application", "action", "log", "comment", "extras", "container", "section", "parentRuleId");
    private final PolicyChangeRepository repository;
    private final ObjectMapper mapper;
    private final TopologyNamePseudonymizer names;
    private final SubnetPreservingIpMasker ips;
    /** Firewalls Panorama names by serial, shown by hostname (PO 2026-10-22); absent in unit tests that set no inventory. */
    private volatile PolicyFirewallNames firewalls;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDeviceRepository(com.securityexpert.nexus.ui2.persistence.device.DeviceRepository devices) {
        this.firewalls = devices == null ? null : new PolicyFirewallNames(devices);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PolicyChangesService(TransactionBoundary transactions, ObjectMapper mapper, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        this.repository = new PolicyChangeRepository(transactions); this.mapper = mapper; this.names = names; this.ips = ips;
    }
    PolicyChangesService(PolicyChangeRepository repository, ObjectMapper mapper, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        this.repository = repository; this.mapper = mapper; this.names = names; this.ips = ips;
    }
    record Cursor(Instant from, Instant to, long ceiling, Position position) {}
    /** includeInitial: also list a policy's initial baseline capture (every rule 'added' when no baseline existed). */
    public record Request(String from, String to, String policy, String device, String actor, String changeType, String field, String q, String cursor, int limit,
                          boolean includeInitial) {
        public Request(String from, String to, String policy, String device, String actor, String changeType, String field, String q, String cursor, int limit) {
            this(from, to, policy, device, actor, changeType, field, q, cursor, limit, true);
        }
    }
    private Cursor cursor(Request request) {
        Cursor parsed = parseCursor(request);
        return parsed.ceiling() < 0 ? new Cursor(parsed.from(), parsed.to(), repository.ceiling(), null) : parsed;
    }
    private Cursor parseCursor(Request request) {
        try {
            if (request.limit() < 1 || request.limit() > 200 || request.q().length() > 1000 || request.actor().length() > 256
                    || request.policy().length() > 1000 || request.device().length() > 1000
                    || (!request.changeType().isEmpty() && !Set.of("added", "modified", "removed").contains(request.changeType()))
                    || (!request.field().isEmpty() && !FIELDS.contains(request.field()))) throw new IllegalArgumentException();
            if (!request.cursor().isEmpty()) {
                if (request.cursor().length() > 1024) throw new IllegalArgumentException();
                String[] bits = new String(Base64.getUrlDecoder().decode(request.cursor()), StandardCharsets.UTF_8).split("\\|", -1);
                if (bits.length != 5) throw new IllegalArgumentException();
                var cursor = new Cursor(Instant.parse(bits[0]), Instant.parse(bits[1]), Long.parseLong(bits[2]), new Position(Instant.parse(bits[3]), Long.parseLong(bits[4])));
                if ((!request.from().isEmpty() && !Instant.parse(request.from()).equals(cursor.from()))
                    || (!request.to().isEmpty() && !Instant.parse(request.to()).equals(cursor.to())) || cursor.ceiling() < 0 || cursor.position().revision() < 0
                    || !cursor.from().isBefore(cursor.to())) throw new IllegalArgumentException();
                return cursor;
            }
            Instant to = request.to().isEmpty() ? Instant.now() : Instant.parse(request.to());
            Instant from = request.from().isEmpty() ? to.minusSeconds(30L * 86400) : Instant.parse(request.from());
            if (!from.isBefore(to)) throw new IllegalArgumentException();
            return new Cursor(from, to, -1, null);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid change filters or cursor"); }
    }
    static String encode(Cursor cursor, Position position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((cursor.from() + "|" + cursor.to() + "|" + cursor.ceiling() + "|" + position.time() + "|" + position.revision()).getBytes(StandardCharsets.UTF_8));
    }
    private Filter filter(Request request, Cursor cursor) {
        return filter(request, cursor, request.includeInitial());
    }
    private Filter filter(Request request, Cursor cursor, boolean includeInitial) {
        return new Filter(cursor.from(), cursor.to(), request.policy(), request.device(), request.changeType(), request.field(), includeInitial);
    }
    /** Filter identities after projection, so raw identity guesses cannot probe AIView. */
    private boolean matches(Map<String, Object> row, Request request) {
        return (request.actor().isEmpty() || String.valueOf(row.get("changedBy")).toLowerCase(Locale.ROOT).contains(request.actor().toLowerCase(Locale.ROOT)))
            && (request.q().isEmpty() || (row.get("name") + " " + row.get("number")).toLowerCase(Locale.ROOT).contains(request.q().toLowerCase(Locale.ROOT)));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> project(Row row, boolean masked) {
        try {
            Map<String, Object> metadata = mapper.readValue(row.metadata(), new TypeReference<>() {});
            List<Map<String, Object>> changes = mapper.readValue(row.changes(), new TypeReference<>() {});
            var out = new LinkedHashMap<String, Object>();
            out.put("revision", String.valueOf(row.revision())); out.put("policyId", row.policy()); out.put("policyName", metadata.getOrDefault("name", "UNKNOWN"));
            var firewalls = this.firewalls;
            out.put("targets", firewalls == null ? metadata.getOrDefault("targets", List.of())
                : firewalls.targets(String.valueOf(metadata.get("vendor")), metadata.getOrDefault("targets", List.of())));
            out.put("ruleId", row.ruleId());
            out.put("currentSnapshotLabel", row.currentLabel()); out.put("number", row.number()); out.put("name", row.name() == null ? "UNKNOWN" : row.name());
            out.put("changedOn", row.time()); out.put("observedAtCollection", row.observed()); out.put("changeType", row.changeType());
            out.put("collectedAt", row.collectedAt()); out.put("initialCapture", row.initialCapture());
            out.put("changedBy", row.actor() == null || row.actor().isBlank() ? UNKNOWN_ACTOR : row.actor());
            out.put("changes", changes); out.put("changedFields", changes.stream().map(c -> c.get("field")).toList());
            if (firewalls != null) {
                var selectors = firewalls.changeSelectors(changes);
                if (!selectors.isEmpty()) out.put("targetDevices", selectors);
            }
            return masked ? (Map<String, Object>) PolicyPrivacy.mask(out, "", names, ips) : out;
        } catch (IOException invalid) { throw new IllegalStateException("Stored policy history is invalid"); }
    }
    public PolicyResponse changes(Request request, boolean masked) {
        Cursor cursor = cursor(request);
        var rows = new ArrayList<Map<String, Object>>(); var actors = new HashSet<String>(); var rules = new HashSet<List<String>>();
        var counts = new LinkedHashMap<String, Long>(Map.of("added", 0L, "modified", 0L, "removed", 0L));
        boolean scannedSummary = !request.actor().isEmpty() || !request.q().isEmpty();
        long total = 0, initial = 0; Position next = null; Position scan = scannedSummary ? null : cursor.position(); boolean more = false;
        // A scanned summary reads initial captures too, so it can count what the default view leaves out.
        Filter rowsFilter = filter(request, cursor, request.includeInitial() || scannedSummary);
        // Bounded batches keep the estate summary independent of page size, without retaining history payloads.
        while (true) {
            var batch = repository.rows(rowsFilter, cursor.ceiling(), scan);
            for (Row row : batch) {
                var view = project(row, masked);
                if (!matches(view, request)) continue;
                if (row.initialCapture()) initial++;
                if (row.initialCapture() && !request.includeInitial()) continue;
                total++; counts.merge(row.changeType(), 1L, Long::sum); rules.add(List.of(row.policy(), row.ruleKey()));
                if (row.actor() != null && !row.actor().isBlank()) actors.add(row.actor());
                Position position = new Position(Instant.parse(row.time()), row.revision());
                boolean after = cursor.position() == null || position.time().isBefore(cursor.position().time())
                    || position.time().equals(cursor.position().time()) && position.revision() < cursor.position().revision();
                if (after && rows.size() < request.limit()) { rows.add(masked ? project(row, false) : view); next = position; }
                else if (after) more = true;
            }
            if (batch.size() < 500 || !scannedSummary && more) break;
            var last = batch.get(batch.size() - 1); scan = new Position(Instant.parse(last.time()), last.revision());
        }
        return new PolicyResponse(Map.of("rows", rows, "summary", scannedSummary ? Map.of("total", total, "actors", actors.size(), "rules", rules.size(), "counts", counts,
                "initialCaptures", initial, "initialCapturesIncluded", request.includeInitial()) : repository.summary(filter(request, cursor), cursor.ceiling()),
            "nextCursor", more && next != null ? encode(cursor, next) : "", "rangeStart", cursor.from().toString(), "rangeEnd", cursor.to().toString()));
    }
    /**
     * The newest rule revisions in {@code [from, to)} for the change feed (Operations > Change feed): the repository's
     * first index batch (at most 500 rows, newest first), projected like {@link #changes}, initial captures left out.
     * {@code device} narrows to policies targeting that device id (empty for the estate). The caller treats a full
     * batch as a bound that was hit, not as the whole period.
     */
    public List<Map<String, Object>> recent(Instant from, Instant to, String device, boolean masked) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("Invalid change range");
        var filter = new Filter(from, to, "", device == null ? "" : device, "", "", false);
        var rows = new ArrayList<Map<String, Object>>();
        for (Row row : repository.rows(filter, repository.ceiling(), null)) {
            if (row.initialCapture()) continue;
            rows.add(project(row, masked));
        }
        return rows;
    }
    /** The size of one repository batch: a result of this many rows means the period holds more. */
    public static final int RECENT_BATCH = 500;
    public void csv(Request request, boolean masked, OutputStream output) throws IOException {
        Cursor cursor = cursor(request); Position scan = null;
        var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write("Time,Observed at collection,Policy,Devices,Rule number,Rule name,Current snapshot label,Change type,Actor,Field,Before,After,Collected at,Initial capture\r\n");
        while (true) {
            var batch = repository.rows(filter(request, cursor), cursor.ceiling(), scan);
            for (Row row : batch) {
                var view = project(row, masked);
                if (!matches(view, request)) continue;
                List<?> diffs = (List<?>) view.get("changes");
                for (Object diff : diffs.isEmpty() ? List.of(Map.of()) : diffs) {
                    var c = (Map<?, ?>) diff;
                    List<Object> cells = Arrays.asList(view.get("changedOn"), view.get("observedAtCollection"), view.get("policyName"),
                        mapper.writeValueAsString(view.get("targets")), view.get("number"), view.get("name"), view.get("currentSnapshotLabel"), view.get("changeType"), view.get("changedBy"),
                        c.get("field"), mapper.writeValueAsString(c.get("before")), mapper.writeValueAsString(c.get("after")),
                        view.get("collectedAt"), view.get("initialCapture"));
                    writer.write(String.join(",", cells.stream().map(PolicyChangesService::csvCell).toList())); writer.write("\r\n");
                }
            }
            writer.flush();
            if (batch.size() < 500) break;
            var last = batch.get(batch.size() - 1); scan = new Position(Instant.parse(last.time()), last.revision());
        }
    }
    public void validate(Request request) { cursor(request); }
    static String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.matches("(?s)^[\\s\\p{Cc}]*[=+@-].*") || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
