package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The few namespaced Kubernetes API calls the script-run broker makes ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment
 * 2026-11-18 (b) item 5). Objects are plain JSON trees. Callers go through {@link ScriptRunKubernetes}, which enforces
 * the {@code script-run-} name prefix that Kubernetes RBAC cannot enforce on {@code create}.
 */
public interface KubernetesApi {

    /** The object kinds the broker's Role allows, and nothing else. */
    enum Kind {
        JOB("apis/batch/v1", "jobs"),
        SECRET("api/v1", "secrets"),
        NETWORK_POLICY("apis/networking.k8s.io/v1", "networkpolicies"),
        POD("api/v1", "pods");

        public final String apiPath;
        public final String plural;

        Kind(String apiPath, String plural) {
            this.apiPath = apiPath;
            this.plural = plural;
        }
    }

    /** A pod log read: the bytes and whether the read stopped at its byte limit. */
    record LogRead(byte[] bytes, boolean limitReached) {
        public LogRead {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    void create(Kind kind, Map<String, Object> object) throws IOException;

    Optional<Map<String, Object>> get(Kind kind, String name) throws IOException;

    List<Map<String, Object>> list(Kind kind, String labelSelector) throws IOException;

    /** Deletes; a missing object is not an error. Jobs are deleted with their pods (propagation Background). */
    void delete(Kind kind, String name) throws IOException;

    LogRead podLog(String podName, String container, long limitBytes) throws IOException;
}
