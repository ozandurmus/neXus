package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Name-prefix discipline for every Kubernetes object the broker touches ({@code SCRIPT_EXECUTION_CONTRACT.md}
 * Amendment 2026-11-18 (b) item 5). Kubernetes RBAC can restrict {@code get}/{@code delete} by {@code resourceNames} but
 * never {@code create} (the name does not exist yet), so the broker's Role grants create/delete on all jobs, secrets and
 * network policies of the namespace and this class is the fence: every name must match {@link #NAME}, every created
 * object must carry that name, the {@code ui2} namespace and the {@code script-run} component label, and pods are only
 * listed by the {@code job-name} of a script-run Job. Anything else is refused before any call is made.
 */
public final class ScriptRunKubernetes {

    public static final String NAMESPACE = "ui2";
    public static final String PREFIX = "script-run-";
    public static final Pattern NAME = Pattern.compile("^script-run-[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$");
    public static final String COMPONENT = "script-run";

    private final KubernetesApi api;

    public ScriptRunKubernetes(KubernetesApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    static String requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("refused: Kubernetes object name outside the script-run- prefix");
        }
        return name;
    }

    @SuppressWarnings("unchecked")
    public void create(KubernetesApi.Kind kind, Map<String, Object> object) throws IOException {
        if (kind == KubernetesApi.Kind.POD) {
            throw new IllegalArgumentException("refused: the broker creates Jobs, never bare pods");
        }
        Map<String, Object> metadata = (Map<String, Object>) object.get("metadata");
        if (metadata == null) {
            throw new IllegalArgumentException("refused: object without metadata");
        }
        requireName((String) metadata.get("name"));
        if (!NAMESPACE.equals(metadata.get("namespace"))) {
            throw new IllegalArgumentException("refused: object outside the ui2 namespace");
        }
        Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");
        if (labels == null || !COMPONENT.equals(labels.get("app.kubernetes.io/component"))) {
            throw new IllegalArgumentException("refused: object without the script-run component label");
        }
        api.create(kind, object);
    }

    public Optional<Map<String, Object>> get(KubernetesApi.Kind kind, String name) throws IOException {
        return api.get(kind, requireName(name));
    }

    public void delete(KubernetesApi.Kind kind, String name) throws IOException {
        if (kind == KubernetesApi.Kind.POD) {
            throw new IllegalArgumentException("refused: pods go with their Job");
        }
        api.delete(kind, requireName(name));
    }

    /** The pods of one script-run Job. */
    public List<Map<String, Object>> podsOfJob(String jobName) throws IOException {
        return api.list(KubernetesApi.Kind.POD, "job-name=" + requireName(jobName));
    }

    /** Every script-run Job in the namespace (for sweeping orphaned runs at broker startup). */
    public List<Map<String, Object>> listJobs() throws IOException {
        return api.list(KubernetesApi.Kind.JOB, "app.kubernetes.io/component=" + COMPONENT);
    }

    public KubernetesApi.LogRead podLog(String podName, String container, long limitBytes) throws IOException {
        if (podName == null || !podName.startsWith(PREFIX) || !podName.matches("[a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("refused: pod name outside the script-run- prefix");
        }
        return api.podLog(podName, container, limitBytes);
    }
}
