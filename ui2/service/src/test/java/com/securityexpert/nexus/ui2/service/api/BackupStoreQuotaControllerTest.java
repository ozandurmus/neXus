package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStoreQuotaRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** Per-store storage quota (V225): usage and warnings on read, validated audited writes, role-gated routes. */
class BackupStoreQuotaControllerTest {

    private static final class FakeRepository implements ArtefactStoreQuotaRepository {
        final Map<String, Long> quotas = new TreeMap<>();
        String lastActor;
        String lastAction;

        @Override public Map<String, Long> findAll() { return new TreeMap<>(quotas); }

        @Override public void set(String store, long bytes, String actor, String action) {
            quotas.put(store, bytes);
            lastActor = actor;
            lastAction = action;
        }

        @Override public boolean clear(String store, String actor, String action) {
            lastActor = actor;
            lastAction = action;
            return quotas.remove(store) != null;
        }
    }

    private static MockHttpServletRequest asActor(String actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, actor);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> body, String store) {
        return ((List<Map<String, Object>>) body.get("stores")).stream()
                .filter(r -> store.equals(r.get("store"))).findFirst().orElseThrow();
    }

    @Test
    void noQuotaMeansUnlimitedAndNoWarning(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("check_point/dev-1"));
        Files.write(root.resolve("check_point/dev-1/a.enc"), new byte[1000]);
        var controller = new BackupStoreQuotaController(new FakeRepository(), root);

        Map<String, Object> check = row(controller.list().getBody(), "check_point");
        assertEquals(1000L, check.get("used_bytes"));
        assertNull(check.get("quota_bytes"));
        assertFalse((Boolean) check.get("near_limit"));
        assertFalse((Boolean) check.get("quota_exceeded"));
        assertFalse(check.containsKey("warning"));
    }

    @Test
    void usageNearAndOverTheQuotaIsWarnedInTheSharedWording(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("check_point"));
        Files.createDirectories(root.resolve("palo_alto"));
        Files.write(root.resolve("check_point/a.enc"), new byte[850]);
        Files.write(root.resolve("palo_alto/b.enc"), new byte[1000]);
        var repository = new FakeRepository();
        repository.quotas.put("check_point", 1000L);
        repository.quotas.put("palo_alto", 1000L);
        repository.quotas.put("never_written", 500L);
        var controller = new BackupStoreQuotaController(repository, root);

        Map<String, Object> body = controller.list().getBody();
        Map<String, Object> near = row(body, "check_point");
        assertTrue((Boolean) near.get("near_limit"));
        assertFalse((Boolean) near.get("quota_exceeded"));
        assertEquals(85.0, near.get("percent"));
        assertEquals(150L, near.get("remaining_bytes"));
        assertTrue(String.valueOf(near.get("warning")).startsWith("artefact_store_near_quota: store=check_point"));
        Map<String, Object> over = row(body, "palo_alto");
        assertTrue((Boolean) over.get("quota_exceeded"));
        assertTrue(String.valueOf(over.get("warning")).startsWith("artefact_store_quota_exceeded: store=palo_alto"));
        assertEquals(0L, row(body, "never_written").get("used_bytes"));
    }

    @Test
    void setAndClearAreAuditedWithTheActionAndTheActor(@TempDir Path root) {
        var repository = new FakeRepository();
        var controller = new BackupStoreQuotaController(repository, root);

        var set = controller.set("check_point", new BackupStoreQuotaController.UpdateRequest(2048L), asActor("actor-1"));
        assertEquals(200, set.getStatusCode().value());
        assertEquals(2048L, repository.quotas.get("check_point"));
        assertEquals("actor-1", repository.lastActor);
        assertEquals(ActionRegistry.BACKUP_STORE_QUOTA_SET, repository.lastAction);
        assertEquals(2048L, row(set.getBody(), "check_point").get("quota_bytes"));

        var cleared = controller.set("check_point", new BackupStoreQuotaController.UpdateRequest(null), asActor("actor-1"));
        assertEquals(200, cleared.getStatusCode().value());
        assertTrue(repository.quotas.isEmpty());
    }

    @Test
    void invalidStoresAndBytesAreRefusedBeforeAnyWrite(@TempDir Path root) {
        var repository = new FakeRepository();
        var controller = new BackupStoreQuotaController(repository, root);
        for (String bad : new String[] {"../etc", "a b", "", "x".repeat(65)}) {
            assertEquals(400, controller.set(bad, new BackupStoreQuotaController.UpdateRequest(1L), asActor("a")).getStatusCode().value(), bad);
        }
        assertEquals(400, controller.set("ok", new BackupStoreQuotaController.UpdateRequest(0L), asActor("a")).getStatusCode().value());
        assertEquals(400, controller.set("ok", new BackupStoreQuotaController.UpdateRequest(-5L), asActor("a")).getStatusCode().value());
        assertEquals(400, controller.set("ok", new BackupStoreQuotaController.UpdateRequest(Long.MAX_VALUE), asActor("a")).getStatusCode().value());
        assertEquals(400, controller.set("ok", null, asActor("a")).getStatusCode().value());
        assertEquals(403, controller.set("ok", new BackupStoreQuotaController.UpdateRequest(1L), new MockHttpServletRequest()).getStatusCode().value());
        assertTrue(repository.quotas.isEmpty());
        assertNull(repository.lastAction);
    }
}
