package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.*;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Read-only computation, with the same CSRF/session boundary as other read-only POSTs. */
@RestController
public final class PolicyLookupController {
    private final PolicyQueryService policies;
    private final AuthzDecisionRepository audit;
    private final TopologyNamePseudonymizer names;
    private final SubnetPreservingIpMasker ips;
    private final Map<String, ArrayDeque<Instant>> requests = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    public PolicyLookupController(PolicyQueryService policies, AuthzDecisionRepository audit,
            TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        this.policies = policies; this.audit = audit; this.names = names; this.ips = ips;
    }
    @PostMapping("/api/v2/policy/policies/{id}/lookup")
    public ResponseEntity<?> lookup(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Object actor = request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        Object session = request.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE);
        if (!(actor instanceof String user) || !(session instanceof String sessionId)) return error(HttpStatus.UNAUTHORIZED);
        if (id.isBlank() || id.length() > 200 || id.chars().anyMatch(Character::isISOControl)) return error(HttpStatus.BAD_REQUEST);
        if (!admit(user, Instant.now())) return ResponseEntity.status(429).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.RETRY_AFTER, "60").body(Map.of("error", "LOOKUP_RATE_LIMIT"));
        PolicyLookup.Query query;
        try {
            if (!Set.of("source", "destination", "service", "sourceZone", "destinationZone", "target", "context").containsAll(body.keySet())
                    || body.values().stream().anyMatch(v -> !(v instanceof String))) throw new IllegalArgumentException();
            query = new PolicyLookup.Query((String) body.get("source"), (String) body.get("destination"), (String) body.get("service"),
                (String) body.get("sourceZone"), (String) body.get("destinationZone"), (String) body.get("target"), (String) body.get("context"));
        } catch (IllegalArgumentException invalid) { return error(HttpStatus.BAD_REQUEST); }
        var snapshot = policies.find(id);
        if (snapshot.isEmpty()) return error(HttpStatus.NOT_FOUND);
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        PolicyResponse result = PolicyLookup.evaluate(masked ? searchable(snapshot.get()) : snapshot.get(), query, 250000);
        try {
            // Append-only authorization audit includes the completed read's masked context; database supplies time.
            var auditQuery = new HashMap<>(query.view());
            auditQuery.put("querySource", ips.maskText(query.source())); auditQuery.put("queryDestination", ips.maskText(query.destination()));
            String evidence = json.writeValueAsString(Map.of("query", PolicyPrivacy.mask(auditQuery, "", names, ips),
                "verdict", result.body().get("verdict")));
            audit.insert(sessionId, user, ActionRegistry.POLICY_READ, Optional.of(id), AuthzOutcome.PERMITTED,
                Optional.of("policy_lookup"), Optional.of(evidence), Optional.empty());
        } catch (JsonProcessingException | RuntimeException failure) { return error(HttpStatus.SERVICE_UNAVAILABLE); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    private PolicySnapshot searchable(PolicySnapshot snapshot) {
        // Match the rule viewer's visible addresses/zones while retaining opaque evaluation-scope identity.
        Map<String, PolicyObject> objects = new HashMap<>();
        snapshot.objects().forEach((id, object) -> objects.put(id, json.convertValue(
            PolicyPrivacy.mask(json.convertValue(object, Map.class), "", names, ips), PolicyObject.class)));
        var sections = snapshot.sections().stream().map(section -> new Section(section.id(), section.name(), section.source(), section.parentRuleId(),
            section.rules().stream().map(rule -> {
                var extras = new HashMap<>(rule.extras());
                for (String field : List.of("from", "to")) if (extras.containsKey(field))
                    extras.put(field, extras.get(field).stream().map(value -> (String) PolicyPrivacy.mask(value, field, names, ips)).toList());
                return new Rule(rule.id(), rule.uuid(), rule.number(), rule.name(), rule.enabled(), rule.source(), rule.destination(), rule.service(),
                    rule.application(), rule.action(), rule.log(), rule.comment(), extras, rule.hitCounts());
            }).toList())).toList();
        var meta = snapshot.metadata();
        var targets = meta.targets().stream().map(target -> new Target(target.deviceId(), target.name(),
            (String) PolicyPrivacy.mask(target.context(), "context", names, ips), target.syncStatus())).toList();
        return new PolicySnapshot(new Metadata(meta.id(), meta.sourceId(), meta.sourceName(), meta.vendor(), meta.containerId(), meta.containerName(),
            meta.name(), meta.collectedAt(), meta.artefactRef(), targets), sections, objects, snapshot.failures());
    }
    private synchronized boolean admit(String actor, Instant now) {
        Instant cutoff = now.minusSeconds(60);
        requests.values().forEach(times -> { while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) times.removeFirst(); });
        requests.values().removeIf(ArrayDeque::isEmpty);
        if (!requests.containsKey(actor) && requests.size() >= 10000) return false;
        var times = requests.computeIfAbsent(actor, key -> new ArrayDeque<>());
        if (times.size() >= 30) return false;
        times.addLast(now); return true;
    }
    private static ResponseEntity<?> error(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", status.name()));
    }
}
