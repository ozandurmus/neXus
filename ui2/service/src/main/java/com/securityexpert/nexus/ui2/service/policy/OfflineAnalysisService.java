package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.OfflineAnalysisRepository;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class OfflineAnalysisService {
    private final OfflineAnalysisRepository repository;
    private final ObjectMapper json;
    public OfflineAnalysisService(TransactionBoundary transactions, ObjectMapper json) {
        repository = new OfflineAnalysisRepository(transactions); this.json = json;
    }
    public String save(String policy, String actor, String filename, Map<String, Object> aggregates) {
        String id = UUID.randomUUID().toString();
        try { repository.save(id, policy, actor, filename, json.writeValueAsString(aggregates)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("OFFLINE_RESULT_INVALID"); }
        return id;
    }
    public List<Map<String, Object>> list(String policy, int page) { return repository.list(policy, page).stream().map(this::read).toList(); }
    public Optional<Map<String, Object>> find(String id) { return repository.find(id).map(this::read); }
    public boolean delete(String id, String actor) { return repository.delete(id, actor); }
    private Map<String, Object> read(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("OFFLINE_RESULT_INVALID"); }
    }
}
