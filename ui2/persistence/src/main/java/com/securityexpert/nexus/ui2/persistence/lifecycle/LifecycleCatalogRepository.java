package com.securityexpert.nexus.ui2.persistence.lifecycle;

import java.util.List;

public interface LifecycleCatalogRepository {
    List<LifecycleCatalogEntry> list();
    record StoredInventory(String model, String version, String licensesJson) { }
    /** Own observed product facts and latest-run licenses; no discovery/parent fallback or topology expansion. */
    java.util.Map<String, StoredInventory> storedInventory();
    /** Atomic upsert by vendor/kind/product; an edit preserves the selected row's identity. */
    void save(List<LifecycleCatalogEntry> entries, String editedId, String actor);
    boolean delete(String catalogId, String actor);
}
