"""B1-1c construction-rule check for the UI 2.0 container image and manifests.

`docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
(FROZEN) §5.1 states ten construction rules, `OS-1` to `OS-10`, and §3 states
the image rules `FS-1` to `FS-7` and `EP-1` to `EP-4`. The rules exist because
the corporate platform's `restricted-v2` security context constraint enforces
them and the local cluster enforces none of them (`PORT-4`): a constraint that
is only satisfied where it is enforced is discovered at the stage where a
refused admission is least recoverable.

This module is the check that keeps them satisfied here. It fails when a
manifest violates a named rule. The 2026-09-30 security baseline also covers
standalone build/bootstrap/e2e workloads and explicitly records exceptions;
passing these source checks does not prove runtime admission.

PyYAML is already a development dependency; parse full YAML, including flow
mappings and CronJob templates, so security checks cover every workload.
"""

from __future__ import annotations

import base64
import json
import sys
import re
from datetime import date
import tomllib
from pathlib import Path

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
CONTAINERFILE = REPO_ROOT / "ui2" / "Containerfile"
MANIFEST_DIR = REPO_ROOT / "deploy" / "ui2"
OPENSHIFT_DIR = MANIFEST_DIR / "openshift"

# §11 check 3 (BP-2): no host container tool may appear in any tracked file
# of the image or manifest path. The build runs inside the cluster.
HOST_CONTAINER_TOOL = re.compile(r"docker |podman |buildah|nerdctl|/var/run/docker\.sock")

# OS-3, OS-4, OS-5, OS-9 and OS-10 text guard. Fixed identities are checked
# structurally below, including exact standalone bootstrap/e2e exceptions.
FORBIDDEN_CONSTRUCT = re.compile(
    r"hostNetwork|hostPID|hostIPC"
    r"|hostPort|privileged: true|NodePort|LoadBalancer|:latest"
)


def load_documents(text: str) -> list[dict]:
    """Parse manifests with the repository's existing YAML dependency."""
    return [doc for doc in yaml.safe_load_all(text) if doc is not None]


# ---------------------------------------------------------------------------
# Fixtures over the manifest set
# ---------------------------------------------------------------------------


def _manifest_files(include_openshift: bool = True) -> list[Path]:
    paths = sorted(MANIFEST_DIR.glob("*.yaml"))
    if include_openshift:
        paths += sorted(OPENSHIFT_DIR.glob("*.yaml"))
    return paths


def _documents(include_openshift: bool = True) -> list[tuple[Path, dict]]:
    out: list[tuple[Path, dict]] = []
    for path in _manifest_files(include_openshift):
        for doc in load_documents(path.read_text(encoding="utf-8")):
            out.append((path, doc))
    return out


def _pod_specs(documents=None) -> list[tuple[Path, str, dict]]:
    """Every pod template in the set, with the file and object it came from."""
    found: list[tuple[Path, str, dict]] = []
    for path, doc in _documents() if documents is None else documents:
        if not doc.get("kind"):
            continue  # a merge patch (deploy/security/notification-mount.patch.yaml), not an object
        name = f"{doc.get('kind')}/{(doc.get('metadata') or {}).get('name')}"
        if doc.get("kind") == "Pod":
            found.append((path, name, doc.get("spec") or {}))
            continue
        spec = doc.get("spec") or {}
        if doc.get("kind") == "CronJob":
            spec = spec["jobTemplate"]["spec"]
        template = (spec.get("template") or {}).get("spec")
        if template:
            found.append((path, name, template))
    return found


def _containers() -> list[tuple[Path, str, dict]]:
    out: list[tuple[Path, str, dict]] = []
    for path, owner, spec in _pod_specs():
        for key in ("initContainers", "containers"):
            for container in spec.get(key) or []:
                out.append((path, f"{owner}:{container.get('name')}", container))
    return out


def test_manifest_set_is_present_and_parsable():
    assert MANIFEST_DIR.is_dir(), "deploy/ui2/ is the path the contract §1 fixes"
    docs = _documents()
    assert docs, "the manifest set parsed to nothing"
    for path, doc in docs:
        assert doc.get("apiVersion"), f"{path.name}: no apiVersion"
        assert doc.get("kind"), f"{path.name}: no kind"


@pytest.mark.parametrize("filename,container_name", [
    ("50-service-deployment.yaml", "service"),
    ("52-worker-deployment.yaml", "worker"),
    ("53-backup-deployment.yaml", "backup"),
    ("57-policy-deployment.yaml", "policy"),
])
def test_failover_mutation_is_enabled_live_and_disabled_in_preview(monkeypatch, filename, container_name):
    monkeypatch.syspath_prepend(str(REPO_ROOT / "tools" / "e2e"))
    import hosta_preview_e2e as preview

    source = next(doc for doc in load_documents((MANIFEST_DIR / filename).read_text())
                  if doc["kind"] == "Deployment")
    candidate = preview.workload_manifest(source, "registry.example.invalid/candidate@sha256:" + "a" * 64,
                                          "192.0.2.10", {
                                              "ui2-compliance": "http://192.0.2.30:8085",
                                              "ui2-configuration": "http://192.0.2.40:8084",
                                          })
    for deployment, expected in ((source, "true"), (candidate, "false")):
        container = next(c for c in deployment["spec"]["template"]["spec"]["containers"]
                         if c["name"] == container_name)
        entries = [e for e in container["env"] if e["name"] == "NEXUS_FAILOVER_MUTATION_ENABLED"]
        assert entries == [{"name": "NEXUS_FAILOVER_MUTATION_ENABLED", "value": expected}]


@pytest.mark.parametrize("filename,container_name", [
    ("52-worker-deployment.yaml", "worker"),
    ("57-policy-deployment.yaml", "policy"),
])
def test_pan_firewall_reads_are_replaced_on_pan_os_12_and_unchanged_below(filename, container_name):
    """PO 2026-10-24 ("PAN-OS 12 icin bunlari yenileri ile degistir"): below PAN-OS 12 show interface all and show session
    info are sent as before; on 12 or later they are replaced (show session meter for failover check 6, interfaces from
    the stored configuration) -- PanFirewallCommandSwitches "replace_12". Earlier values: "below_12" (12+ not collected),
    "retired" (never sent). Removing the entries sends the original reads always."""
    deployment = next(doc for doc in load_documents((MANIFEST_DIR / filename).read_text())
                      if doc["kind"] == "Deployment")
    container = next(c for c in deployment["spec"]["template"]["spec"]["containers"] if c["name"] == container_name)
    env = {e["name"]: e.get("value") for e in container["env"]}
    assert env.get("NEXUS_PAN_FW_SHOW_INTERFACE_ALL") == "replace_12"
    assert env.get("NEXUS_PAN_FW_SHOW_SESSION_INFO") == "replace_12"


def _container_env(filename, container_name):
    deployment = next(doc for doc in load_documents((MANIFEST_DIR / filename).read_text())
                      if doc["kind"] == "Deployment")
    container = next(c for c in deployment["spec"]["template"]["spec"]["containers"] if c["name"] == container_name)
    return {e["name"]: e for e in container["env"]}


@pytest.mark.parametrize("filename,container_name", [
    ("52-worker-deployment.yaml", "worker"),
    ("57-policy-deployment.yaml", "policy"),
])
def test_one_shell_pilot_switch_is_in_the_manifest_and_the_cluster_list_is_never_a_literal(filename, container_name):
    """PO 2026-10-22 one-shell pilot: the switch is a literal; the cluster allow-list holds real cluster refs, so it
    comes from the hand-made ConfigMap ui2-failover-pilot (optional: missing = no clusters), never from the repo."""
    env = _container_env(filename, container_name)
    assert env["NEXUS_CP_FAILOVER_ONE_SHELL"] == {"name": "NEXUS_CP_FAILOVER_ONE_SHELL", "value": "enabled"}
    clusters = env["NEXUS_CP_FAILOVER_ONE_SHELL_CLUSTERS"]
    assert "value" not in clusters
    assert clusters["valueFrom"] == {"configMapKeyRef": {
        "name": "ui2-failover-pilot", "key": "one_shell_clusters", "optional": True}}


def test_the_failover_pilot_configmap_is_not_committed():
    for path in _manifest_files():
        for doc in load_documents(path.read_text()):
            assert not (doc["kind"] == "ConfigMap" and doc["metadata"]["name"] == "ui2-failover-pilot"), path.name


def test_backup_polling_is_every_30_seconds_in_the_worker():
    """PO 2026-10-25: fewer Gaia audit log-ins per backup (code default 10). UI2_MDS_EXPORT_* is deliberately
    untouched (MDS backup stays disabled by PO decision)."""
    for filename, container_name in (("52-worker-deployment.yaml", "worker"), ("53-backup-deployment.yaml", "backup")):
        env = _container_env(filename, container_name)
        assert env["UI2_BACKUP_POLL_INTERVAL_SECONDS"] == {"name": "UI2_BACKUP_POLL_INTERVAL_SECONDS", "value": "30"}


def test_scheduled_system_log_collection_is_enabled_for_the_fleet_in_the_service_only(monkeypatch):
    """PO decision 2026-10-18 and PO 2026-11-05: SystemLogCollectionScheduler is a Spring bean of the service process,
    so only the service deployment carries the switches; the code defaults stay off."""
    monkeypatch.syspath_prepend(str(REPO_ROOT / "tools" / "e2e"))
    import hosta_preview_e2e as preview

    for name in (
        "UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED",
        "UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_INDEPENDENT",
    ):
        for path, owner, spec in _pod_specs():
            for container in spec.get("containers") or []:
                entries = [e for e in container.get("env") or [] if e["name"] == name]
                if owner == "Deployment/ui2-service":
                    assert entries == [{"name": name, "value": "true"}], f"{owner}: {name} must be on"
                else:
                    assert not entries, f"{owner}: the scheduler does not run here; {name} belongs to the service only"

    service = next(doc for doc in load_documents((MANIFEST_DIR / "50-service-deployment.yaml").read_text())
                   if doc["kind"] == "Deployment")
    candidate = preview.workload_manifest(service, "registry.example.invalid/candidate@sha256:" + "a" * 64,
                                          "192.0.2.10", {
                                              "ui2-compliance": "http://192.0.2.30:8085",
                                              "ui2-configuration": "http://192.0.2.40:8084",
                                          })
    container = next(c for c in candidate["spec"]["template"]["spec"]["containers"] if c["name"] == "service")
    assert {"name": "UI2_SCHEDULING_ENABLED", "value": "false"} in container["env"]

    application = (REPO_ROOT / "ui2/service/src/main/resources/application.yml").read_text(encoding="utf-8")
    assert "enabled: ${UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED:false}" in application, "the code default stays false"
    assert "independent: ${UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_INDEPENDENT:false}" in application, "the code default stays false"


def test_the_rule_checks_below_have_something_to_check():
    """A rule asserted over an empty list passes without proving anything.

    Every per-container check below iterates what the reader found, so this
    test is what stops a reader that silently degraded to nothing from
    turning the whole module green.
    """
    pod_specs = _pod_specs()
    assert {owner for _, owner, _ in pod_specs} == {
        "Job/ui2-flyway-bootstrap", "StatefulSet/ui2-db",
        "Deployment/ui2-service", "Deployment/ui2-worker",
        "Deployment/ui2-configuration", "Deployment/ui2-compliance", "Deployment/ui2-policy",
        "Deployment/ui2-backup",
        "Deployment/ui2-packet-analysis",
        "Deployment/ui2-script-runner",
        "Job/ui2-e2e", "CronJob/ui2-e2e",
        "CronJob/ui2-advisory-fetch",
    }
    assert len(_containers()) == len(pod_specs)


def test_manifest_set_contains_every_kind_the_contract_names():
    """§5.2 names seven kinds; DB-2 adds the database StatefulSet."""
    kinds = [doc.get("kind") for _, doc in _documents(include_openshift=False)]
    for required in (
        "Namespace",
        "ConfigMap",
        "Secret",
        "PersistentVolumeClaim",
        "Deployment",
        "Ingress",
        "StatefulSet",
    ):
        assert required in kinds, f"§5.2: the set has no {required}"
    assert kinds.count("Service") == 5, "service, database, worker, configuration and compliance"
    assert kinds.count("Secret") == 6, (
        "§5.2: the db-credentials secret plus the worker's credential-store, "
        "role-binding, configuration-artefact-store, privacy-HMAC and hostname-fingerprint key secrets "
        "(NXS-LOCAL-0165: 23-secret-artefact-store-key.yaml; "
        "NXS-LOCAL-0170: 24-secret-hostname-fingerprint-key.yaml)"
    )
    assert kinds.count("PersistentVolumeClaim") == 4, (
        "§5.2 plus BK-17: the database data claim, the worker-only recovery volume claim "
        "(NXS-LOCAL-0170: 35-artefact-store-pvc.yaml), the Packet Analysis volume "
        "(PO decision record 2026-10-15 §5: 36-packet-analysis-pvc.yaml) and the Reports R1 report-file claim "
        "(37-report-store-pvc.yaml), and never a fifth"
    )


def test_route_is_a_separate_file_and_the_only_platform_difference():
    """PORT-1 and PORT-3."""
    local_kinds = sorted(
        {doc.get("kind") for _, doc in _documents(include_openshift=False)}
    )
    assert "Route" not in local_kinds, "PORT-3: the Route is applied instead of the Ingress"
    route_files = sorted(OPENSHIFT_DIR.glob("*.yaml"))
    assert route_files, "PORT-1: the Route manifest is missing"
    openshift_kinds = []
    for path in route_files:
        for doc in load_documents(path.read_text(encoding="utf-8")):
            openshift_kinds.append(doc.get("kind"))
    assert openshift_kinds == ["Route"], (
        "PORT-1: the corporate platform differs by the Route alone, "
        f"but the substitution carries {openshift_kinds}"
    )
    assert "Ingress" in local_kinds


@pytest.mark.parametrize("path", _manifest_files())
def test_no_host_container_tool_appears(path: Path):
    """BP-2 / §11 check 3."""
    match = HOST_CONTAINER_TOOL.search(path.read_text(encoding="utf-8"))
    assert match is None, f"{path.name}: host container tool reference {match.group(0)!r}"


def test_no_host_container_tool_in_the_containerfile():
    """BP-2 / §11 check 3."""
    match = HOST_CONTAINER_TOOL.search(CONTAINERFILE.read_text(encoding="utf-8"))
    assert match is None, f"ui2/Containerfile: host container tool reference {match.group(0)!r}"


@pytest.mark.parametrize("path", _manifest_files())
def test_no_forbidden_construct_appears(path: Path):
    """OS-2, OS-3, OS-4, OS-5, OS-9, OS-10 — §11 check 4."""
    match = FORBIDDEN_CONSTRUCT.search(path.read_text(encoding="utf-8"))
    assert match is None, f"{path.name}: forbidden construct {match.group(0)!r}"


# OS-3a (amendment 2026-09-24): the only hostPath is a vendor push inbox.
PUSH_INBOX_PATH = re.compile(r"^/var/lib/nexus-[a-z0-9-]+/in$")
WORKER_OWNERS = {"Deployment/ui2-worker", "Deployment/ui2-backup"}


def _is_push_inbox(owner: str, volume: dict) -> bool:
    host = volume.get("hostPath")
    return (isinstance(host, dict) and owner in WORKER_OWNERS and bool(PUSH_INBOX_PATH.match(str(host.get("path", ""))))
            and host.get("type") == "Directory")


def test_every_host_path_is_a_worker_push_inbox():
    """OS-3 / OS-3a: a hostPath only as a worker-owned vendor push inbox, never in the OpenShift overlay."""
    for path, owner, spec in _pod_specs():
        for volume in spec.get("volumes") or []:
            if "hostPath" in volume:
                assert OPENSHIFT_DIR not in path.parents, f"{path.name}: the OpenShift overlay carries no hostPath (OS-3a.6)"
                assert _is_push_inbox(owner, volume), (
                    f"{path.name}: {owner} volume {volume.get('name')!r} is a hostPath that is not a worker push inbox "
                    "(/var/lib/nexus-<product>/in, type Directory) -- OS-3a")
    for path in _manifest_files():
        text = path.read_text(encoding="utf-8")
        for m in re.finditer(r"hostPath", text):
            assert path.name in {"52-worker-deployment.yaml", "53-backup-deployment.yaml"}, (
                f"{path.name}: hostPath outside the worker Deployment (OS-3a.3)")


def test_no_pod_spec_requests_a_fixed_identity():
    """FS-7 / OS-2 for application workloads; exact standalone job identities."""
    for path, owner, spec in _pod_specs():
        security = spec.get("securityContext") or {}
        # Standalone bootstrap/e2e images need explicit non-root identities.
        # Long-running application/database workloads retain arbitrary-uid admission.
        fixed = {
            "Job/ui2-flyway-bootstrap": {"runAsUser": 10001},
            "Job/ui2-e2e": {"runAsUser": 1000, "runAsGroup": 1000, "fsGroup": 1000},
            "CronJob/ui2-e2e": {"runAsUser": 1000, "runAsGroup": 1000, "fsGroup": 1000},
        }.get(owner, {})
        assert {key: security[key] for key in ("runAsUser", "runAsGroup", "fsGroup") if key in security} == fixed, (
            f"{path.name}: {owner} has an unexpected fixed identity"
        )
    for path, owner, container in _containers():
        security = container.get("securityContext") or {}
        for field in ("runAsUser", "runAsGroup"):
            assert field not in security, f"{path.name}: {owner} sets {field}"


def test_every_container_runs_non_root():
    """OS-1."""
    for path, owner, spec in _pod_specs():
        pod_security = spec.get("securityContext") or {}
        for container in spec.get("initContainers", []) + spec.get("containers", []):
            security = pod_security | (container.get("securityContext") or {})
            assert security.get("runAsNonRoot") is True, f"{path.name}: {owner} may run as root"


def test_every_container_refuses_privilege_escalation():
    """OS-4."""
    for path, owner, container in _containers():
        security = container.get("securityContext") or {}
        assert security.get("privileged") is not True, f"{path.name}: {owner} is privileged"
        assert security.get("allowPrivilegeEscalation") is False, (
            f"{path.name}: {owner} does not set allowPrivilegeEscalation: false"
        )


def test_every_container_drops_capabilities_and_takes_the_default_seccomp_profile():
    """OS-7."""
    for path, owner, container in _containers():
        security = container.get("securityContext") or {}
        capabilities = security.get("capabilities") or {}
        assert capabilities.get("drop") == ["ALL"], f"{path.name}: {owner} does not drop ALL"
    for path, owner, spec in _pod_specs():
        pod_security = spec.get("securityContext") or {}
        for container in spec.get("initContainers", []) + spec.get("containers", []):
            security = pod_security | (container.get("securityContext") or {})
            assert security.get("seccompProfile", {}).get("type") == "RuntimeDefault", (
                f"{path.name}: {owner} does not take the RuntimeDefault seccomp profile"
            )


def test_every_container_has_a_read_only_root_filesystem():
    """OS-8 / FS-4."""
    for path, owner, container in _containers():
        security = container.get("securityContext") or {}
        assert security.get("readOnlyRootFilesystem") is True, (
            f"{path.name}: {owner} does not set readOnlyRootFilesystem: true"
        )


def test_every_container_declares_requests_and_limits():
    """OS-6: all four of cpu and memory, request and limit."""
    for path, owner, container in _containers():
        resources = container.get("resources") or {}
        for section in ("requests", "limits"):
            values = resources.get(section) or {}
            for resource in ("cpu", "memory"):
                if owner in {"Deployment/ui2-policy:policy", "Deployment/ui2-backup:backup"} and section == "limits" and resource == "cpu":
                    assert "cpu" not in values
                    continue
                assert values.get(resource) is not None, (
                    f"{path.name}: {owner} declares no {section}.{resource}"
                )


def test_policy_snapshot_memory_budget_and_postgresql_config_mount():
    containers = {owner: container for _, owner, container in _containers()}
    database = containers["StatefulSet/ui2-db:database"]
    assert database["resources"] == {
        "requests": {"cpu": "100m", "memory": "1Gi"},
        "limits": {"cpu": "4", "memory": "6Gi"},
    }
    assert containers["Deployment/ui2-policy:policy"]["resources"]["limits"]["memory"] == "8Gi"
    assert containers["Deployment/ui2-service:service"]["resources"]["limits"]["memory"] == "8Gi"
    mount = next(m for m in database["volumeMounts"] if m["name"] == "database-config")
    assert mount["mountPath"] == "/opt/app-root/src/postgresql-cfg"
    assert mount["readOnly"] is True
    spec = next(spec for _, owner, spec in _pod_specs() if owner == "StatefulSet/ui2-db")
    config = next(v["configMap"] for v in spec["volumes"] if v["name"] == "database-config")
    assert config["name"] == "ui2-db-config"
    assert config["optional"] is True
    assert config["items"] == [{"key": "policy-memory.conf", "path": "policy-memory.conf"}]
    documents = load_documents((MANIFEST_DIR / "40-database-statefulset.yaml").read_text())
    tuning = next(doc for doc in documents if doc["kind"] == "ConfigMap")
    assert tuning["metadata"]["name"] == config["name"]
    assert tuning["metadata"]["namespace"] == "ui2"
    data = tuning["data"]
    assert data["policy-memory.conf"].splitlines() == [
        "shared_buffers = '1GB'", "work_mem = '32MB'", "maintenance_work_mem = '256MB'",
    ]
    common = load_documents((MANIFEST_DIR / "10-configmap.yaml").read_text())[0]
    assert "policy-memory.conf" not in common["data"]


def test_every_writable_path_is_supplied_as_a_mount():
    """FS-4: with a read-only root filesystem, a written path is a mount."""
    for path, owner, spec in _pod_specs():
        volumes = {volume.get("name"): volume for volume in spec.get("volumes") or []}
        for container in spec.get("containers") or []:
            for mount in container.get("volumeMounts") or []:
                name = mount.get("name")
                assert name in volumes, f"{path.name}: {owner} mounts unknown volume {name!r}"
                volume = volumes[name]
                assert "hostPath" not in volume or _is_push_inbox(owner, volume), f"{path.name}: {owner} mounts a node path"
                assert set(volume) & {"emptyDir", "persistentVolumeClaim", "configMap", "secret", "hostPath"}, (
                    f"{path.name}: {owner} mounts {name!r} from an unexpected source"
                )


def test_the_service_declares_exactly_the_writable_set_the_image_declares():
    """FS-3 and FS-4: the JVM temporary directory and the declared HOME."""
    declared = _containerfile_declared_writable_paths()
    assert declared == {"/app/tmp", "/app/home"}, (
        f"ui2/Containerfile declares an unexpected writable set: {sorted(declared)}"
    )
    service = _service_container()
    empty_dir_names = {
        volume["name"]
        for volume in _service_pod_spec().get("volumes") or []
        if "emptyDir" in volume
    }
    mounted = {
        mount["mountPath"]
        for mount in service.get("volumeMounts") or []
        if mount.get("name") in empty_dir_names
    }
    assert mounted == declared, (
        "FS-4: each declared writable path is supplied as an emptyDir mount, "
        f"but the mounted set is {sorted(mounted)}"
    )


def test_the_recovery_volume_is_separate_and_only_the_worker_can_write():
    """Recovery data is separate; the service download path is read-only."""
    pvc_names = {
        (doc.get("metadata") or {}).get("name")
        for _, doc in _documents(include_openshift=False)
        if doc.get("kind") == "PersistentVolumeClaim"
    }
    assert "ui2-db-data" in pvc_names, "the database's own data claim must still exist"
    assert "ui2-artefact-store" in pvc_names, "BK-17: the recovery volume claim is missing"
    assert pvc_names == {"ui2-db-data", "ui2-artefact-store", "ui2-packet-analysis-data", "ui2-report-store"}, (
        f"unexpected PersistentVolumeClaim set: {sorted(pvc_names)}"
    )

    for _, owner, spec in _pod_specs():
        volumes = {volume.get("name"): volume for volume in spec.get("volumes") or []}
        recovery_volume_names = {
            name for name, volume in volumes.items()
            if (volume.get("persistentVolumeClaim") or {}).get("claimName") == "ui2-artefact-store"
        }
        mounted_recovery_names = {
            mount["name"]
            for container in spec.get("containers") or []
            for mount in container.get("volumeMounts") or []
            if mount["name"] in recovery_volume_names
        }
        if owner.startswith("Deployment/ui2-worker") or owner in {"Deployment/ui2-policy", "Deployment/ui2-backup"}:
            assert mounted_recovery_names, "BK-17: the worker Deployment must mount the recovery volume"
        elif owner == "Deployment/ui2-service":
            for container in spec.get("containers") or []:
                for mount in container.get("volumeMounts") or []:
                    if mount["name"] in recovery_volume_names:
                        assert mount.get("readOnly") is True
            for name in recovery_volume_names:
                assert volumes[name]["persistentVolumeClaim"].get("readOnly") is True
        else:
            assert not recovery_volume_names, f"{owner}: unexpected recovery volume"



def test_report_files_have_their_own_claim_and_the_service_writes_nothing_else_of_the_artefact_store():
    """Reports R1: report files sit on ui2-report-store at UI2_REPORT_STORE_ROOT; BK-17 stays intact."""
    seen = {}
    for _, owner, spec in _pod_specs():
        volumes = {volume.get("name"): volume for volume in spec.get("volumes") or []}
        report_names = {
            name for name, volume in volumes.items()
            if (volume.get("persistentVolumeClaim") or {}).get("claimName") == "ui2-report-store"
        }
        mounts = [
            mount for container in spec.get("containers") or []
            for mount in container.get("volumeMounts") or [] if mount["name"] in report_names
        ]
        if not report_names:
            continue
        seen[owner] = mounts
        assert all(m["mountPath"] == "/app/report-store" and not m.get("subPath") for m in mounts), (
            f"{owner}: the report claim must be mounted at /app/report-store"
        )
        env = {e["name"]: e.get("value") for c in spec.get("containers") or [] for e in c.get("env") or []}
        assert env.get("UI2_REPORT_STORE_ROOT") == "/app/report-store", f"{owner}: UI2_REPORT_STORE_ROOT must name it"
        assert all(m.get("readOnly") is not True for m in mounts), f"{owner}: the report claim must be writable"
    assert set(seen) == {"Deployment/ui2-service", "Deployment/ui2-worker", "Deployment/ui2-backup"}, (
        f"only the service and the worker mount the report claim, got {sorted(seen)}"
    )
    assert all(seen.values()), "both the service and the worker must mount the report claim"


def test_packet_analysis_worker_is_low_priority_bounded_and_isolated():
    """PO decision record 2026-10-15 §4/§5: a dedicated, limited, lower-priority analysis worker."""
    documents = load_documents((MANIFEST_DIR / "58-packet-analysis-deployment.yaml").read_text(encoding="utf-8"))
    priority, = [doc for doc in documents if doc["kind"] == "PriorityClass"]
    assert priority["metadata"]["name"] == "ui2-low"
    assert priority["value"] < 0, "below the default priority (0)"
    assert priority["globalDefault"] is False and priority["preemptionPolicy"] == "Never"
    deployment, = [doc for doc in documents if doc["kind"] == "Deployment"]
    spec = deployment["spec"]["template"]["spec"]
    assert spec["priorityClassName"] == "ui2-low"
    others = {owner: pod for _, owner, pod in _pod_specs() if owner.startswith("Deployment/")}
    for owner, pod in others.items():
        if owner != "Deployment/ui2-packet-analysis":
            assert pod.get("priorityClassName") != "ui2-low", f"{owner}: only the analysis worker is low priority"
    container, = spec["containers"]
    assert container["args"] == ["worker", "packet_analysis"]
    assert container["resources"]["limits"] == {"cpu": "4", "memory": "24Gi"}
    _assert_packet_analysis_memory_plan(container)
    assert deployment["spec"]["replicas"] == 1 and deployment["spec"]["strategy"] == {"type": "Recreate"}
    mounts = {mount["name"]: mount for mount in container["volumeMounts"]}
    assert mounts["packet-analysis-data"]["mountPath"] == "/app/packet-analysis"
    assert mounts["packet-analysis-data"].get("readOnly") is not True
    volumes = {volume["name"]: volume for volume in spec["volumes"]}
    assert volumes["packet-analysis-data"]["persistentVolumeClaim"]["claimName"] == "ui2-packet-analysis-data"
    # No device credential, no credential-store or artefact-store key, no recovery volume.
    secret_names = {(volume.get("secret") or {}).get("secretName") for volume in spec["volumes"]}
    assert secret_names - {None} == {"ui2-db"}
    assert all((volume.get("persistentVolumeClaim") or {}).get("claimName") != "ui2-artefact-store" for volume in spec["volumes"])
    policy, = [doc for doc in documents if doc["kind"] == "NetworkPolicy"]
    ports = {port["port"] for rule in policy["spec"]["egress"] for port in rule.get("ports", [])}
    assert ports == {53, 5432}, "analysis makes no outbound call"
    assert "TODO(packet-analysis image split)" in (MANIFEST_DIR / "58-packet-analysis-deployment.yaml").read_text()


def _assert_packet_analysis_memory_plan(container: dict) -> None:
    """Pod limit = JVM heap + DuckDB memory_limit + reserve (ui2/packet-analysis/README.md, "Memory plan").

    DuckDB's limit bounds its buffer manager only; the heap, metaspace, direct buffers, native memory and the tshark
    child count against the pod limit too, so the three must add up with the reserve the engine itself enforces
    (max(2 GiB, pod / 8)), and spill must be bounded and fit the analysis volume.
    """
    import re

    mib = 1024 ** 2
    env = {entry["name"]: entry.get("value") for entry in container["env"]}
    pod = int(container["resources"]["limits"]["memory"].removesuffix("Gi")) * 1024  # MiB
    options = env["JAVA_TOOL_OPTIONS"]
    heap = int(re.search(r"-Xmx(\d+)g", options).group(1)) * 1024
    metaspace = int(re.search(r"-XX:MaxMetaspaceSize=(\d+)m", options).group(1))
    direct = int(re.search(r"-XX:MaxDirectMemorySize=(\d+)g", options).group(1)) * 1024
    assert "MaxRAMPercentage" not in options, "the heap is sized explicitly, not as a share of the pod"
    duckdb = int(env["UI2_PACKET_ANALYSIS_DUCKDB_MEMORY_MB"])
    reserve = max(2048, pod // 8)
    assert (pod, heap, duckdb) == (24576, 6144, 14336)
    assert duckdb + heap + reserve <= pod, "DuckDB + heap + reserve must fit the pod limit"
    # The 4 GiB left beside heap and DuckDB covers the bounded JVM native areas with margin for the tshark child.
    assert pod - heap - duckdb >= metaspace + direct + 2048
    assert int(env["UI2_PACKET_ANALYSIS_DUCKDB_THREADS"]) == 4
    # Spill goes to the analysis volume (<case>/.spill), bounded below the claim (120Gi, shared with the captures).
    assert int(env["UI2_PACKET_ANALYSIS_MAX_TEMP_MB"]) == 40 * 1024
    assert int(env["UI2_PACKET_ANALYSIS_MAX_TEMP_MB"]) * mib < 120 * 1024 ** 3
    # DuckDB's own spill ceiling is soft; the spill guard's hard limit leaves at least half the claim for captures.
    assert int(env["UI2_PACKET_ANALYSIS_MAX_TEMP_MB"]) <= int(env["UI2_PACKET_ANALYSIS_HARD_TEMP_MB"]) == 60 * 1024
    assert int(env["UI2_PACKET_ANALYSIS_HARD_TEMP_MB"]) * mib <= 60 * 1024 ** 3
    assert int(env["UI2_PACKET_ANALYSIS_MAX_PACKETS_PER_CAPTURE"]) == 150000


def test_only_the_service_and_the_analysis_worker_mount_the_analysis_volume():
    claim = next(doc for _, doc in _documents(include_openshift=False)
                 if doc.get("kind") == "PersistentVolumeClaim" and doc["metadata"]["name"] == "ui2-packet-analysis-data")
    assert claim["spec"]["accessModes"] == ["ReadWriteOnce"], "DuckDB is single-writer: never RWX/NFS"
    mounted = {owner for _, owner, spec in _pod_specs() for volume in spec.get("volumes") or []
               if (volume.get("persistentVolumeClaim") or {}).get("claimName") == "ui2-packet-analysis-data"}
    assert mounted == {"Deployment/ui2-service", "Deployment/ui2-packet-analysis"}
    env = {entry["name"]: entry.get("value") for entry in _service_container()["env"]}
    assert env["UI2_PACKET_ANALYSIS_MAX_FILE_BYTES"] == str(2 * 1024 ** 3)
    assert env["UI2_PACKET_ANALYSIS_MAX_STORE_BYTES"] == str(100 * 1024 ** 3)


def _service_pod_spec() -> dict:
    for _, owner, spec in _pod_specs():
        if owner.startswith("Deployment/ui2-service"):
            return spec
    raise AssertionError("the service Deployment is missing from the set")


def _service_container() -> dict:
    for container in _service_pod_spec().get("containers") or []:
        if container.get("name") == "service":
            return container
    raise AssertionError("the service container is missing from the Deployment")


def _worker_pod_spec() -> dict:
    for _, owner, spec in _pod_specs():
        if owner.startswith("Deployment/ui2-worker"):
            return spec
    raise AssertionError("the worker Deployment is missing from the set")


def _worker_container() -> dict:
    for container in _worker_pod_spec().get("containers") or []:
        if container.get("name") == "worker":
            return container
    raise AssertionError("the worker container is missing from the Deployment")


def test_the_artefact_store_is_backed_by_a_claim_not_an_emptydir():
    """NXS-LOCAL-0167 / C7 section 4: a worker restart must not lose an
    artefact that a `device_configuration_run` row still points at by
    `artefact_ref` -- the emptyDir NXS-LOCAL-0165 shipped was a
    local-validation shortcut, not a design. Every other writable path
    (tmp, home) keeps its existing emptyDir assertion untouched.
    """
    spec = _worker_pod_spec()
    volumes = {volume.get("name"): volume for volume in spec.get("volumes") or []}
    artefact_volume = volumes.get("artefact-store")
    assert artefact_volume is not None, "the worker Deployment declares no artefact-store volume"
    assert "persistentVolumeClaim" in artefact_volume, (
        "the artefact-store volume must be a PersistentVolumeClaim, "
        f"found: {sorted(artefact_volume)}"
    )
    assert "emptyDir" not in artefact_volume, (
        "the artefact-store volume is still an emptyDir -- artefacts do not survive a worker restart"
    )
    claim_name = artefact_volume["persistentVolumeClaim"].get("claimName")
    assert claim_name, "the artefact-store volume names no claim"

    claim_docs = [
        doc
        for _, doc in _documents(include_openshift=False)
        if doc.get("kind") == "PersistentVolumeClaim"
        and (doc.get("metadata") or {}).get("name") == claim_name
    ]
    assert claim_docs, f"no PersistentVolumeClaim manifest named {claim_name!r}"
    claim_spec = claim_docs[0].get("spec") or {}
    assert claim_spec.get("accessModes") == ["ReadWriteOnce"], (
        f"{claim_name}: expected accessModes [ReadWriteOnce], found {claim_spec.get('accessModes')}"
    )
    assert (claim_spec.get("resources") or {}).get("requests", {}).get("storage"), (
        f"{claim_name}: no storage request"
    )

    for name in ("tmp", "home"):
        assert "emptyDir" in volumes[name], (
            f"{name}: expected to remain an emptyDir, found {sorted(volumes[name])}"
        )


def test_the_service_artefact_download_mount_is_read_only():
    """The existing download route reads artefacts; only the worker writes them."""
    service_mount = next(
        mount for mount in _service_container()["volumeMounts"]
        if mount["name"] == "artefact-store"
    )
    assert service_mount.get("readOnly") is True
    worker_mount = next(
        mount for mount in _worker_container()["volumeMounts"]
        if mount["name"] == "artefact-store"
    )
    assert worker_mount.get("readOnly") is not True


def _containerfile_declared_writable_paths() -> set[str]:
    text = CONTAINERFILE.read_text(encoding="utf-8")
    match = re.search(r"chmod 0770 ([^\n\\]+)", text)
    assert match, "ui2/Containerfile no longer declares its writable set"
    return set(match.group(1).split())


E2E_IMAGE_PLACEHOLDER = "registry.kube-system.svc.cluster.local/nexus-ui2-e2e:SET_AT_DEPLOY"


def test_every_image_reference_is_immutable():
    """OS-10 / BP-7."""
    for path, owner, container in _containers():
        image = container.get("image")
        assert isinstance(image, str) and image, f"{path.name}: {owner} has no image"
        if image == E2E_IMAGE_PLACEHOLDER:
            continue  # tools/e2e/hosta_e2e.sh substitutes the built digest before apply
        assert "@sha256:" in image, f"{path.name}: {owner} is not pinned by digest: {image}"


def test_the_secret_manifest_carries_no_value():
    """SEC-2 / §11 check 6."""
    for path, doc in _documents():
        if doc.get("kind") != "Secret":
            continue
        text = path.read_text(encoding="utf-8")
        for forbidden in ("\ndata:", "\nstringData:"):
            assert forbidden not in text, (
                f"{path.name}: a tracked Secret file carries a {forbidden.strip()} block"
            )
    for path, doc in _documents():
        if doc.get("kind") != "Secret":
            continue
        assert "data" not in doc, f"{path.name}: the Secret carries data"
        assert "stringData" not in doc, f"{path.name}: the Secret carries stringData"


def test_the_credential_reaches_the_container_only_by_reference():
    """SEC-4: no environment entry carries a literal value for a credential."""
    referenced = False
    for path, owner, container in _containers():
        for entry in container.get("env") or []:
            name = str(entry.get("name", ""))
            if entry.get("valueFrom") is not None:
                if "secretKeyRef" in (entry.get("valueFrom") or {}):
                    referenced = True
                continue
            value = entry.get("value")
            assert not re.search(r"PASSWORD$|SECRET$|TOKEN$", name), (
                f"{path.name}: {owner} sets {name} to a literal value"
            )
            if value is not None and "_FILE" not in name:
                assert "password" not in str(value).lower(), (
                    f"{path.name}: {owner} sets {name} to something that reads as a credential"
                )
    assert referenced, "SEC-4: no credential reaches a container by reference"


# ---------------------------------------------------------------------------
# The image, §11 checks 1 and 2
# ---------------------------------------------------------------------------


def test_the_containerfile_is_two_stages_pinned_by_digest():
    """§11 check 1 (§3.1, §3.2)."""
    lines = CONTAINERFILE.read_text(encoding="utf-8").splitlines()
    froms = [line for line in lines if line.startswith("FROM ")]
    # §3.2 successor (2026-09-14): the frontend is compiled in its own Node
    # stage (PR #238's no-committed-bundle rule; grafting node into the JDK
    # stage fails on OPENSSL_3.4.0), so the image is N build stages and ONE
    # runtime stage. What §3.2 protects -- a runtime stage that carries no
    # build tooling and bases pinned by digest -- is asserted directly.
    assert len(froms) >= 2, f"§3.2: at least a build and a runtime stage, found {len(froms)}"
    assert "openjdk-21-runtime@sha256:" in froms[-1], f"§3.2: the final stage must be the runtime base: {froms[-1]}"
    assert "nodejs" not in froms[-1] and " AS " not in froms[-1], "§3.2: the runtime stage is last and unnamed"
    for line in froms:
        assert "@sha256:" in line, f"§3.1: base image is not pinned by digest: {line}"


RUNTIME_PACKAGE_UPDATE = (
    "RUN rpm -qa --qf '%{NEVRA}\\n' | sort > /tmp/rpm-before.txt \\\n"
    " && ok=0 rc=0 \\\n"
    " && for attempt in 1 2 3; do \\\n"
    "      timeout 180 microdnf -y update --setopt=install_weak_deps=0 && { ok=1; break; }; \\\n"
    "      rc=$?; \\\n"
    "      printf 'PACKAGE_UPDATE_RETRY: attempt %s failed (exit %s)\\n' \"$attempt\" \"$rc\" >&2; \\\n"
    "      [ \"$attempt\" = 3 ] || sleep \"${PACKAGE_UPDATE_RETRY_SLEEP:-30}\"; \\\n"
    "    done \\\n"
    " && if [ \"$ok\" = 1 ]; then \\\n"
    "      rpm -qa --qf '%{NEVRA}\\n' | sort > /tmp/rpm-after.txt \\\n"
    "      && comm -13 /tmp/rpm-before.txt /tmp/rpm-after.txt | tee /app/build-info/package-update.txt; \\\n"
    "    else \\\n"
    "      printf 'PACKAGE_UPDATE_SKIPPED: microdnf update failed after 3 attempts (exit %s, date=%s); continuing with pinned base packages\\n' \"$rc\" \"${PACKAGE_UPDATE_DATE}\" \\\n"
    "        | tee /app/build-info/package-update.txt >&2; \\\n"
    "    fi \\\n"
    " && (microdnf clean all || true) \\\n"
    " && rm -rf /var/cache/yum /var/cache/dnf /tmp/rpm-before.txt /tmp/rpm-after.txt \\\n"
    " && test -f /app/build-info/package-update.txt"
)


def test_the_runtime_removes_all_python_rpms_offline_and_verifies_in_the_same_run():
    text = CONTAINERFILE.read_text(encoding="utf-8")
    runtime = text.rsplit("\nFROM ", 1)[1]
    removal = "RUN rpm -e --nodeps $(rpm -qa --qf '%{NAME}\\n' 'python3*')"
    verification = ' && test -z "$(rpm -qa \'python3*\')"'
    assert runtime.count(RUNTIME_PACKAGE_UPDATE) == 1
    assert removal + " \\\n" + verification in runtime
    assert (
        runtime.index("USER 0\n")
        < runtime.index(RUNTIME_PACKAGE_UPDATE)
        < runtime.index(removal)
        < runtime.index("USER 185\n")
    )
    assert not re.search(r"\b(?:dnf|microdnf|yum)\b", runtime.replace(RUNTIME_PACKAGE_UPDATE, "", 1))
    entries = yaml.safe_load((REPO_ROOT / "security/baseline.yaml").read_text())["accepted"]
    assert not {"CVE-2026-19553", "CVE-2026-57585"} & {entry["rule"] for entry in entries}


def test_the_runtime_updates_installed_packages_resiliently_and_derived_image_inherits_it(tmp_path):
    """PO decision 2026-11-18 ('Evet, ekle', PO_DECISION_RECORD_2026_11_18_RUNTIME_PACKAGE_UPDATE.md):
    one resilient microdnf update step in the runtime stage before python removal, with daily cache-buster ARG,
    recording updated NEVRAs to /app/build-info/package-update.txt and continuing with PACKAGE_UPDATE_SKIPPED
    when the Red Hat repos are unreachable through the proxy."""
    import os
    import subprocess

    text = CONTAINERFILE.read_text(encoding="utf-8")
    runtime = text.rsplit("\nFROM ", 1)[1]
    removal = "RUN rpm -e --nodeps $(rpm -qa --qf '%{NAME}\\n' 'python3*')"
    arg_match = re.search(r"^ARG PACKAGE_UPDATE_DATE=(\d{4}-\d{2}-\d{2})$", runtime, flags=re.MULTILINE)
    assert arg_match is not None, "daily cache-buster ARG PACKAGE_UPDATE_DATE=YYYY-MM-DD is required"
    date.fromisoformat(arg_match.group(1))
    assert (
        runtime.index("USER 0\n")
        < runtime.index("mkdir -p /app /app/tmp /app/home /app/build-info")
        < runtime.index("update-ca-trust")
        < runtime.index(arg_match.group(0))
        < runtime.index(RUNTIME_PACKAGE_UPDATE)
        < runtime.index(removal)
        < runtime.index("chown -R 0:0 /app")
        < runtime.index("USER 185\n")
    )
    assert '"-XX:-UseBASE64Intrinsics"' in runtime

    # Packet-analysis derived image inherits the updated runtime stage via FROM ${SERVICE_IMAGE} without duplicating it.
    pa_final = (REPO_ROOT / "ui2/Containerfile.packet-analysis").read_text(encoding="utf-8").rsplit("\nFROM ", 1)[1]
    assert pa_final.startswith("${SERVICE_IMAGE}\n")
    assert not re.search(r"\b(?:dnf|microdnf|yum)\b", pa_final)
    assert "package-update.txt" not in pa_final

    # Decision record and successor index link.
    record = (REPO_ROOT / "docs/design/PO_DECISION_RECORD_2026_11_18_RUNTIME_PACKAGE_UPDATE.md").read_text(encoding="utf-8")
    assert "RATIFIED" in record and "Evet, ekle" in record
    assert "PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md" in record

    # Execute the shell body of RUNTIME_PACKAGE_UPDATE against local stubs for both success and proxy-failure fallback.
    shell_body = RUNTIME_PACKAGE_UPDATE.removeprefix("RUN ").replace("\\\n", " ")
    for path_token in ("/app/build-info", "/tmp/rpm-before.txt", "/tmp/rpm-after.txt", "/var/cache/yum", "/var/cache/dnf"):
        shell_body = shell_body.replace(path_token, str(tmp_path) + path_token)
    (tmp_path / "app/build-info").mkdir(parents=True)
    (tmp_path / "tmp").mkdir(parents=True)
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    rpm_stub = bin_dir / "rpm"
    rpm_stub.write_text(
        '#!/bin/sh\n'
        'if [ -f "$TEST_UPDATED" ]; then\n'
        '  printf "bash-5.1.8-9.el9.x86_64\\ncurl-minimal-7.76.1-42.el9_9.x86_64\\nglibc-2.34-290.el9_9.x86_64\\n"\n'
        'else\n'
        '  printf "bash-5.1.8-9.el9.x86_64\\ncurl-minimal-7.76.1-40.el9_8.9.x86_64\\nglibc-2.34-284.el9_8.4.x86_64\\n"\n'
        'fi\n'
    )
    timeout_stub = bin_dir / "timeout"
    timeout_stub.write_text('#!/bin/sh\nshift\nexec "$@"\n')
    microdnf_stub = bin_dir / "microdnf"
    microdnf_stub.write_text(
        '#!/bin/sh\n'
        'if [ "$1" = "clean" ]; then exit "$TEST_CLEAN_RC"; fi\n'
        'echo x >> "$TEST_ATTEMPTS"\n'
        'if [ "$(wc -l < "$TEST_ATTEMPTS")" -le "${TEST_FAIL_FIRST:-0}" ]; then exit 124; fi\n'
        'if [ "$TEST_UPDATE_RC" = "0" ]; then touch "$TEST_UPDATED"; fi\n'
        'exit "$TEST_UPDATE_RC"\n'
    )
    for stub in (rpm_stub, timeout_stub, microdnf_stub):
        stub.chmod(0o755)

    base_env = {
        **os.environ,
        "PATH": f"{bin_dir}:{os.environ['PATH']}",
        "PACKAGE_UPDATE_DATE": "2026-11-18",
        "TEST_UPDATED": str(tmp_path / "updated.flag"),
        "TEST_CLEAN_RC": "0",
        "TEST_ATTEMPTS": str(tmp_path / "attempts.log"),
        "PACKAGE_UPDATE_RETRY_SLEEP": "0",
    }
    report_file = tmp_path / "app/build-info/package-update.txt"

    # 1. Success: updated NEVRAs are written to /app/build-info/package-update.txt and stdout; caches/temps are cleaned.
    for cache_dir in (tmp_path / "var/cache/yum", tmp_path / "var/cache/dnf"):
        cache_dir.mkdir(parents=True, exist_ok=True)
        (cache_dir / "repomd.xml").write_text("cached")
    ok = subprocess.run(["sh", "-c", shell_body], env={**base_env, "TEST_UPDATE_RC": "0"}, capture_output=True, text=True)
    assert ok.returncode == 0, ok.stderr
    expected_nevras = "curl-minimal-7.76.1-42.el9_9.x86_64\nglibc-2.34-290.el9_9.x86_64\n"
    assert ok.stdout == expected_nevras
    assert report_file.read_text() == expected_nevras
    assert "PACKAGE_UPDATE_SKIPPED" not in ok.stdout + ok.stderr + report_file.read_text()
    assert not (tmp_path / "var/cache/yum").exists() and not (tmp_path / "var/cache/dnf").exists()
    assert not (tmp_path / "tmp/rpm-before.txt").exists() and not (tmp_path / "tmp/rpm-after.txt").exists()

    # 2. Proxy/repo failure: logs PACKAGE_UPDATE_SKIPPED, writes it to package-update.txt, cleans caches, and exits 0.
    (tmp_path / "updated.flag").unlink()
    (tmp_path / "var/cache/yum").mkdir(parents=True, exist_ok=True)
    fallback = subprocess.run(
        ["sh", "-c", shell_body],
        env={**base_env, "TEST_UPDATE_RC": "124", "TEST_CLEAN_RC": "1"},
        capture_output=True,
        text=True,
    )
    assert fallback.returncode == 0
    skipped = "PACKAGE_UPDATE_SKIPPED: microdnf update failed after 3 attempts (exit 124, date=2026-11-18)"
    assert skipped in fallback.stderr
    assert fallback.stderr.count("PACKAGE_UPDATE_RETRY: attempt") == 3
    assert report_file.read_text().startswith(skipped) and "PACKAGE_UPDATE_RETRY" not in report_file.read_text()
    assert not (tmp_path / "var/cache/yum").exists()

    # 3. Intermittent proxy 503 (2026-11-21): the first attempt fails, the second updates; no SKIPPED is recorded.
    (tmp_path / "attempts.log").unlink()
    retried = subprocess.run(
        ["sh", "-c", shell_body],
        env={**base_env, "TEST_UPDATE_RC": "0", "TEST_FAIL_FIRST": "1"},
        capture_output=True,
        text=True,
    )
    assert retried.returncode == 0, retried.stderr
    assert "PACKAGE_UPDATE_RETRY: attempt 1 failed (exit 124)" in retried.stderr
    assert "attempt 2" not in retried.stderr and "PACKAGE_UPDATE_SKIPPED" not in retried.stderr
    assert report_file.read_text() == expected_nevras


def test_the_containerfile_final_account_is_numeric():
    """§11 check 2 (FS-6)."""
    text = CONTAINERFILE.read_text(encoding="utf-8")
    named = re.search(r"USER +[A-Za-z]", text)
    assert named is None, "FS-6: the image declares a non-numeric account"
    accounts = re.findall(r"^USER +(\d+)", text, flags=re.MULTILINE)
    assert accounts, "FS-6: the image declares no account"
    assert accounts[-1] != "0", "FS-6: the image's final account is root"


def test_the_entry_point_is_the_jvm_with_a_role_argument():
    """EP-1 and EP-3."""
    text = CONTAINERFILE.read_text(encoding="utf-8")
    assert re.search(r'^ENTRYPOINT \["java"', text, flags=re.MULTILINE), (
        "EP-3: the entry point is the JVM directly, with no shell wrapper"
    )
    assert re.search(r'^CMD \["service"\]', text, flags=re.MULTILINE), (
        "EP-1/EP-2: the workload role is an argument, and `service` is the only one"
    )


def test_every_deploy_workload_has_security_context_or_exact_documented_exception():
    """Cover all deploy subdirectories, standalone Pods, templates and CronJobs."""
    baseline = yaml.safe_load((REPO_ROOT / "security/baseline.yaml").read_text())["accepted"]
    exceptions = {
        # Trivy reports misconfigurations relative to the scanned deploy/ directory; the baseline keeps its form.
        (f"deploy/{entry['location']}", entry["workload"], entry["container"], entry["field"]): entry
        for entry in baseline if "field" in entry
    }
    expected_exceptions = {
        (f"deploy/ui2-image-build/{filename}", owner, "builder", field)
        for filename, owner in (
            ("30-build-job.yaml", "Job/ui2-image-build"),
            ("31-build-job-proxy.yaml.template", "Job/ui2-image-build"),
            ("32-e2e-build-job.yaml", "Job/ui2-e2e-image-build"),
        )
        for field in ("pod.runAsNonRoot", "container.readOnlyRootFilesystem", "container.capabilities.drop")
    }
    assert set(exceptions) == expected_exceptions
    documents = []
    for path in sorted((REPO_ROOT / "deploy").rglob("*")):
        if path.name.endswith((".yaml", ".yml", ".yaml.template")):
            documents.extend((path, doc) for doc in load_documents(path.read_text()))
    workloads = _pod_specs(documents)
    assert {owner.split("/")[0] for _, owner, _ in workloads} == {
        "Deployment", "StatefulSet", "Job", "CronJob", "Pod"
    }
    used = set()
    for path, owner, spec in workloads:
        location = path.relative_to(REPO_ROOT).as_posix()
        pod_security = spec.get("securityContext", {})
        assert pod_security.get("seccompProfile") == {"type": "RuntimeDefault"}, location
        for container in spec.get("initContainers", []) + spec.get("containers", []):
            security = container.get("securityContext", {})
            assert security.get("allowPrivilegeEscalation") is False, location
            assert security.get("privileged") is not True, location
            assert security.get("seccompProfile", pod_security["seccompProfile"]) == {"type": "RuntimeDefault"}, location
            checks = {
                "pod.runAsNonRoot": pod_security.get("runAsNonRoot") is True
                and security.get("runAsNonRoot", True) is True
                and pod_security.get("runAsUser") != 0 and security.get("runAsUser") != 0,
                "container.readOnlyRootFilesystem": security.get("readOnlyRootFilesystem") is True,
                "container.capabilities.drop": security.get("capabilities", {}).get("drop") == ["ALL"]
                and not security.get("capabilities", {}).get("add"),
            }
            for field, compliant in checks.items():
                key = (location, owner, container["name"], field)
                if compliant:
                    assert key not in exceptions, f"Remove resolved exception: {key}"
                    continue
                assert key in exceptions, f"Missing security control: {key}"
                entry = exceptions[key]
                assert entry["reason"] and entry["owner"] == "PO" and entry["review_date"] == "2026-12-31"
                assert "# Security exception:" in path.read_text() and "security/baseline.yaml" in path.read_text()
                used.add(key)
    assert used == set(exceptions), "Stale security exceptions"


def test_every_kaniko_args_list_carries_image_download_retry(monkeypatch):
    """Every Kaniko build template and generated build Job args list must carry --image-download-retry=5
    (next to --cache=false where --cache=false is used) to survive transient proxy EOFs on base layers."""
    import copy
    import json

    monkeypatch.syspath_prepend(str(REPO_ROOT / "tools" / "e2e"))
    monkeypatch.syspath_prepend(str(REPO_ROOT / "tools" / "delivery"))
    monkeypatch.syspath_prepend(str(REPO_ROOT / "tools" / "security"))
    import hosta_e2e_image as e2e_image
    import hosta_packet_analysis_image as pa_image
    import hosta_preview_e2e as preview
    import hosta_restamp_image as restamp
    import mirror_security_images as mirror

    def assert_retry_flag(args: list[str], label: str) -> None:
        assert "--image-download-retry=5" in args, f"{label}: missing --image-download-retry=5"
        assert sum(a.startswith("--image-download-retry=") for a in args) == 1, f"{label}: duplicate retry flag"
        if "--cache=false" in args:
            assert args[args.index("--cache=false") + 1] == "--image-download-retry=5", (
                f"{label}: --image-download-retry=5 must follow --cache=false"
            )

    documents = []
    for path in sorted((REPO_ROOT / "deploy").rglob("*")):
        if path.name.endswith((".yaml", ".yml", ".yaml.template")):
            documents.extend((path, doc) for doc in load_documents(path.read_text()))
    kaniko_templates = set()
    for path, owner, spec in _pod_specs(documents):
        location = path.relative_to(REPO_ROOT).as_posix()
        for container in spec.get("initContainers", []) + spec.get("containers", []):
            if "kaniko-project/executor" in container.get("image", ""):
                kaniko_templates.add(location)
                assert_retry_flag(container.get("args", []), f"{location}:{owner}:{container['name']}")
    assert kaniko_templates == {
        "deploy/ui2-image-build/30-build-job.yaml",
        "deploy/ui2-image-build/31-build-job-proxy.yaml.template",
        "deploy/ui2-image-build/32-e2e-build-job.yaml",
    }

    commit = "a" * 40
    base = restamp.REPOSITORY + "@sha256:" + "1" * 64
    loader = next(
        doc for doc in load_documents((REPO_ROOT / "deploy/ui2-image-build/20-context-loader.yaml").read_text())
        if doc["kind"] == "Pod"
    )
    for template_name in ("30-build-job.yaml", "31-build-job-proxy.yaml.template"):
        raw_template = next(
            doc for doc in load_documents((REPO_ROOT / "deploy/ui2-image-build" / template_name).read_text())
            if doc["kind"] == "Job"
        )
        stripped_template = copy.deepcopy(raw_template)
        stripped_template["spec"]["template"]["spec"]["containers"][0]["args"] = [
            a for a in stripped_template["spec"]["template"]["spec"]["containers"][0]["args"]
            if not a.startswith("--image-download-retry=")
        ]
        for variant_label, tpl in (("committed", raw_template), ("stripped", stripped_template)):
            for dockerfile in ("ui2/Containerfile", "ui2/frontend/Dockerfile.e2e"):
                job = preview.build_manifest(
                    tpl, loader, "synthetic-preview", commit, "synthetic-owner",
                    dockerfile, "registry.example.invalid/preview:" + commit[:12],
                )
                assert_retry_flag(
                    job["spec"]["template"]["spec"]["containers"][0]["args"],
                    f"preview.build_manifest({template_name},{variant_label},{dockerfile})",
                )
            assert_retry_flag(
                restamp.builder_args(tpl["spec"]["template"]["spec"]["containers"][0]["args"], commit, base),
                f"restamp.builder_args({template_name},{variant_label})",
            )
            assert_retry_flag(
                pa_image.builder_args(tpl["spec"]["template"]["spec"]["containers"][0]["args"], commit, base),
                f"pa_image.builder_args({template_name},{variant_label})",
            )

    e2e_doc = next(
        doc for doc in load_documents((REPO_ROOT / "deploy/ui2-image-build/32-e2e-build-job.yaml").read_text())
        if doc["kind"] == "Job"
    )
    proxy_doc = next(
        doc for doc in load_documents((REPO_ROOT / "deploy/ui2-image-build/31-build-job-proxy.yaml.template").read_text())
        if doc["kind"] == "Job"
    )
    created_e2e = []
    monkeypatch.setattr(
        e2e_image, "kubectl",
        lambda *args, **kwargs: (
            json.dumps({"data": {"commit": commit}}) if args[:2] == ("get", "configmap")
            else json.dumps(e2e_doc if "32-e2e-build-job.yaml" in str(args) else proxy_doc) if "--dry-run=client" in args
            else (created_e2e.append(json.loads(kwargs["input"])) or "") if args[0] == "create"
            else ""
        ),
    )
    e2e_image.start(commit, REPO_ROOT)
    assert_retry_flag(created_e2e[0]["spec"]["template"]["spec"]["containers"][0]["args"], "hosta_e2e_image.start")

    created_mirror = []
    digest = "sha256:" + "2" * 64
    monkeypatch.setattr(
        mirror, "command",
        lambda *args, **kwargs: (
            (created_mirror.append(json.loads(kwargs["input"].decode())) or b"") if args[:2] == ("kubectl", "create")
            else json.dumps({"status": {"conditions": [{"type": "Complete", "status": "True"}]}}).encode() if "job" in args and "get" in args
            else json.dumps({"items": [{"status": {"containerStatuses": [{"state": {"terminated": {"message": digest}}}]}}]}).encode() if "pods" in args
            else b""
        ),
    )
    mirror.mirror("trivy", "aquasec/trivy@" + digest, "http://192.0.2.10:3128")
    assert_retry_flag(created_mirror[0]["spec"]["template"]["spec"]["containers"][0]["args"], "mirror_security_images.mirror")


def test_all_java_workloads_mount_the_images_writable_paths():
    for _, owner, spec in _pod_specs():
        if not owner.startswith("Deployment/"):
            continue
        empty_dirs = {volume["name"] for volume in spec.get("volumes", []) if "emptyDir" in volume}
        for container in spec["containers"]:
            mounted = {mount["mountPath"] for mount in container.get("volumeMounts", []) if mount["name"] in empty_dirs}
            assert _containerfile_declared_writable_paths() <= mounted, owner


def test_security_baseline_requires_owned_scoped_acceptances():
    entries = yaml.safe_load((REPO_ROOT / "security/baseline.yaml").read_text())["accepted"]
    identities = [(entry["tool"], entry["rule"], entry["location"]) for entry in entries]
    assert len(identities) == len(set(identities)), "Duplicate accepted findings"
    for entry in entries:
        assert all(entry[key] for key in ("tool", "rule", "location", "reason"))
        assert entry["owner"] == "PO" and date.fromisoformat(entry["review_date"]) <= date(2026, 12, 31)
        assert "*" not in entry["location"], "Accept individual findings, never directory wildcards"
    # The scanner reports the full rule id, so an acceptance only matches under that id (the short name never did).
    tls = [entry for entry in entries if entry["rule"].endswith(".weak-ssl-context")]
    # PAN (PO_DECISION_RECORD_2026_09_21) and the appliance pinning trust manager
    # (PO_DECISION_RECORD_2026_09_30_HTTPS_APPLIANCE_CERTIFICATE_PINNING). The LDAP and cluster-API contexts name
    # "TLSv1.3" (same defaults as "TLS") and need no acceptance.
    assert sorted(entry["location"].rsplit("/", 1)[1] for entry in tls) == [
        "HttpsDeviceClient.java:131", "PanXmlApiTransport.java:307"
    ]
    assert not [entry for entry in entries if entry["rule"] in ("weak-ssl-context", "unencrypted-socket")], "short rule ids never match"


def test_gitleaks_allowlists_only_the_approved_commit_and_seven_exact_test_paths():
    config = tomllib.loads((REPO_ROOT / ".gitleaks.toml").read_text())
    assert config["extend"] == {"useDefault": True}
    assert "rules" not in config, "Keep the default detection rules intact"
    synthetic, history, fixtures, syslog, asa_history, saml_signatures = config["allowlists"]
    # 2026-11-16: Java signatures "PrivateKey key, X509Certificate" in the SAML code, exact match text only.
    assert saml_signatures["targetRules"] == ["generic-api-key"]
    assert saml_signatures["regexTarget"] == "match"
    assert saml_signatures["regexes"] == ["(?i)key, X509Certificate"]
    assert not {"paths", "commits", "stopwords"} & saml_signatures.keys()
    # 2026-11-16: one batch-13 history commit with a synthetic RSA key block, private-key rule only.
    assert asa_history["commits"] == ["7f945da813d64baddbdac7f6e94bc4629cc49754"]
    assert asa_history["targetRules"] == ["private-key"]
    assert not {"paths", "regexes", "stopwords"} & asa_history.keys()
    assert synthetic["targetRules"] == ["generic-api-key"]
    assert synthetic["regexTarget"] == "secret"
    assert synthetic["regexes"] == [
        "^synthetic-value$", "^synthetic-inventory-mask-key-0001$",
    ] + ["^" + base64.b64encode(value.encode()).decode() + "$"
         for value in ("synthetic-user", "synthetic-pass")]
    assert not {"commits", "paths", "stopwords"} & synthetic.keys()
    for pattern in synthetic["regexes"]:
        value = pattern[1:-1]
        assert re.fullmatch(pattern, value)
        assert not re.search(pattern, "prefix-" + value)
        assert not re.search(pattern, value + "-suffix")
    assert history["commits"] == ["8288cb4cd61178d049d348da39d95816f5e93852",
                                  "42ee5549bbbe89672e62d029043331404d736438"]
    assert history["targetRules"] == ["generic-api-key"]
    assert not {"paths", "regexes", "stopwords"} & history.keys()
    assert fixtures["targetRules"] == ["generic-api-key"]
    assert not {"commits", "regexes", "stopwords"} & fixtures.keys()
    entries = yaml.safe_load((REPO_ROOT / "security/baseline.yaml").read_text())["accepted"]
    # System-log redaction/masking tests (phase B): four exact paths, two rules, no other exemption.
    syslog_paths = {
        "ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/systemlog/redaction/LogRedactorTest.java",
        "ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/systemlog/LogMaskingTest.java",
        "ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/systemlog/SystemLogReadFixture.java",
        "ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/systemlog/LogMaskingIdentityTest.java",
    }
    assert syslog["targetRules"] == ["generic-api-key", "private-key"]
    assert not {"commits", "regexes", "stopwords", "regexTarget"} & syslog.keys()
    assert set(syslog["paths"]) == {"^" + re.escape(path).replace(r"\-", "-") + "$" for path in syslog_paths}
    for path in syslog_paths:
        assert (REPO_ROOT / path).is_file()
        for unrelated in ("prefix/" + path, path + ".bak", path.replace("/src/test/", "/src/main/")):
            assert not any(re.search(pattern, unrelated) for pattern in syslog["paths"])
    syslog_entries = {(e["rule"], e["location"]) for e in entries if e["location"] in syslog_paths}
    redactor = "ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/systemlog/redaction/LogRedactorTest.java"
    assert syslog_entries == {("generic-api-key", p) for p in syslog_paths} | {("private-key", redactor)}
    paths = {entry["location"] for entry in entries if entry["rule"] == "generic-api-key" and "commit" not in entry
             and entry["location"] not in syslog_paths}
    assert len(paths) == len(fixtures["paths"]) == 7
    assert set(fixtures["paths"]) == {"^" + re.escape(path).replace(r"\-", "-") + "$" for path in paths}
    for path in paths:
        assert "/src/test/java/" in path and (REPO_ROOT / path).is_file()
        assert sum(bool(re.search(pattern, path)) for pattern in fixtures["paths"]) == 1
        for unrelated in ("prefix/" + path, path + ".bak", path.replace("/src/test/", "/src/main/")):
            assert not any(re.search(pattern, unrelated) for pattern in fixtures["paths"])


def test_integration_job_uses_native_sidecar_and_build_jdk():
    path = REPO_ROOT / "deploy/ui2-image-build/33-integration-postgres.yaml"
    job = yaml.safe_load(path.read_text())
    assert job["kind"] == "Job" and job["metadata"]["namespace"] == "ui2-build"
    assert job["spec"]["activeDeadlineSeconds"] == 3600
    assert job["spec"]["ttlSecondsAfterFinished"] == 300
    assert job["spec"]["backoffLimit"] == 0
    spec = job["spec"]["template"]["spec"]
    postgres, = spec["initContainers"]
    runner, = spec["containers"]
    assert postgres["restartPolicy"] == "Always"
    assert postgres["args"] == ["-c", "listen_addresses=127.0.0.1"]
    assert postgres["startupProbe"]["exec"]["command"] == ["pg_isready", "-h", "127.0.0.1", "-U", "postgres"]
    assert f'FROM {runner["image"]} AS build' in CONTAINERFILE.read_text()
    assert "@sha256:" in postgres["image"]
    env = {e["name"]: e["value"] for e in runner["env"]}
    assert env["UI2_TEST_JDBC_URL"] == "jdbc:postgresql://127.0.0.1:5432/postgres"
    assert env["GRADLE_USER_HOME"] == "/gradle-home"
    # No volume may be mounted below /workspace: the runtime would create the
    # parent directories root-owned and the source archive could not be unpacked.
    mounts = {m["mountPath"]: m["name"] for m in runner["volumeMounts"]}
    assert not [path for path in mounts if path.startswith("/workspace/")]
    assert mounts["/gradle-home"] == "gradle-home" and mounts["/gradle-home/caches"] == "gradle-cache"
    assert spec["automountServiceAccountToken"] is False
    assert any(v.get("configMap") == {"name": "corp-ca"} for v in spec["volumes"])
    for container in (postgres, runner):
        assert container["resources"]["requests"] and container["resources"]["limits"]


def test_the_advisory_fetch_workload_holds_nothing_and_reaches_only_dns_and_the_ingest_port():
    """docs/design/ADVISORY_MATCHING_CONTRACT.md section 3: the one workload that reaches the internet has no neXus
    key, no database account and no service-account token; the committed policy allows DNS and the service's internal
    port only, and the proxy address is never committed."""
    path = MANIFEST_DIR / "59-advisory-fetch-cronjob.yaml"
    text = path.read_text(encoding="utf-8")
    documents = {doc["kind"]: doc for doc in load_documents(text)}
    assert set(documents) == {"ServiceAccount", "NetworkPolicy", "CronJob"}
    assert documents["ServiceAccount"]["automountServiceAccountToken"] is False

    cron = documents["CronJob"]
    assert cron["spec"]["suspend"] is True and cron["spec"]["concurrencyPolicy"] == "Forbid"
    job = cron["spec"]["jobTemplate"]["spec"]
    assert job["backoffLimit"] == 0
    pod = job["template"]["spec"]
    assert pod["automountServiceAccountToken"] is False and pod["serviceAccountName"] == "ui2-advisory-fetch"
    assert job["template"]["metadata"]["labels"]["app.kubernetes.io/component"] == "advisory-fetch"
    assert all("emptyDir" in volume for volume in pod["volumes"]), "no secret, claim or host volume"
    (container,) = pod["containers"]
    assert container["args"] == ["advisory-fetch"]
    env = {entry["name"]: entry for entry in container["env"]}
    # One name per line: "..._TOKEN", "<next name>" on one line reads as a key=value pair to gitleaks.
    assert set(env) == {
        "UI2_ADVISORY_PROXY",
        "UI2_ADVISORY_FETCH_TOKEN",
        "UI2_ADVISORY_INGEST_URL",
        "UI2_ADVISORY_CISCO_DAYS",
        "JAVA_TOOL_OPTIONS",
        "NEXUS_LOG_RAW_ADDRESSES",
    }
    assert env["UI2_ADVISORY_PROXY"]["valueFrom"]["configMapKeyRef"] == {
        "name": "ui2-advisory-fetch", "key": "proxy", "optional": True}
    assert env["UI2_ADVISORY_FETCH_TOKEN"]["valueFrom"]["secretKeyRef"] == {
        "name": "ui2-advisory-fetch-token", "key": "token", "optional": True}
    referenced = {entry["valueFrom"]["secretKeyRef"]["name"] for entry in container["env"]
                  if "secretKeyRef" in entry.get("valueFrom", {})}
    assert referenced == {"ui2-advisory-fetch-token"}, "its own token is its only secret"
    for forbidden in ("ui2-db", "credential-store", "artefact-store", "hostname-fingerprint", "privacy-hmac", "role-binding"):
        assert forbidden not in json_dump(pod), forbidden

    policy = documents["NetworkPolicy"]["spec"]
    assert policy["podSelector"] == {"matchLabels": {"app.kubernetes.io/component": "advisory-fetch"}}
    assert policy["policyTypes"] == ["Ingress", "Egress"] and policy["ingress"] == []
    ports = sorted((port["protocol"], port["port"]) for rule in policy["egress"] for port in rule["ports"])
    assert ports == [("TCP", 53), ("TCP", 8086), ("UDP", 53)]
    assert "ipBlock" not in text, "the proxy address is deployment configuration, never committed"
    assert not re.search(r"\b\d{1,3}(\.\d{1,3}){3}\b", text), "no address literal in this manifest"


def json_dump(value) -> str:
    import json
    return json.dumps(value, sort_keys=True)



# ---------------------------------------------------------------------------
# Metrics port (enterprise phase 0.4, docs/operations/METRICS.md)
# ---------------------------------------------------------------------------


def _all_docs() -> list[dict]:
    docs: list[dict] = []
    for path in sorted(MANIFEST_DIR.glob("*.yaml")):
        docs.extend(load_documents(path.read_text(encoding="utf-8")))
    return docs


def test_metrics_port_is_a_named_container_port_only() -> None:
    """The scrape port is declared on the service and every worker-entry-point container and nowhere that routes traffic."""
    docs = _all_docs()
    with_port = []
    for doc in docs:
        if doc.get("kind") != "Deployment":
            continue
        for container in doc["spec"]["template"]["spec"]["containers"]:
            for port in container.get("ports", []):
                if port.get("name") == "metrics":
                    assert port["containerPort"] == 9404
                    env = {e["name"]: e.get("value") for e in container.get("env", [])}
                    assert env.get("UI2_METRICS_PORT") == "9404"
                    with_port.append(doc["metadata"]["name"])
    assert sorted(with_port) == [
        "ui2-compliance", "ui2-configuration", "ui2-packet-analysis", "ui2-policy", "ui2-service", "ui2-worker"]
    for doc in docs:
        if doc.get("kind") == "Service":
            for port in doc["spec"].get("ports", []):
                assert port.get("name") != "metrics"
                assert port.get("targetPort") not in ("metrics", 9404)
                assert port.get("port") != 9404
        if doc.get("kind") == "Ingress":
            assert "9404" not in yaml.safe_dump(doc) and "metrics" not in yaml.safe_dump(doc)
        if doc.get("kind") == "NetworkPolicy":
            assert "9404" not in yaml.safe_dump(doc), "this change does not touch NetworkPolicy"
    for path in sorted(OPENSHIFT_DIR.glob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        assert "9404" not in text and "metrics" not in text


def test_live_workloads_show_addresses_in_application_logs_and_the_code_default_stays_masked(monkeypatch):
    """PO 2026-11-15: application logs on HOST-A show IP addresses, so every container running the ui2-service image
    sets NEXUS_LOG_RAW_ADDRESSES=1 in the manifest (a ship re-applies manifest env). The code default stays masked, and
    the preview candidate keeps masking. Only the logback/JUL layer reads the switch: aiview, diagnostics and exports
    use their own masking."""
    monkeypatch.syspath_prepend(str(REPO_ROOT / "tools" / "e2e"))
    import hosta_preview_e2e as preview

    app = [(path, owner, c) for path, owner, c in _containers() if "nexus-ui2-service@" in c.get("image", "")]
    owners = {owner.split(":")[0] for _, owner, _ in app}
    assert {"Deployment/ui2-service", "Deployment/ui2-worker", "Deployment/ui2-configuration",
            "Deployment/ui2-policy", "Deployment/ui2-compliance", "Deployment/ui2-packet-analysis",
            "CronJob/ui2-advisory-fetch"} <= owners
    for _, owner, container in app:
        entries = [e for e in container.get("env") or [] if e["name"] == "NEXUS_LOG_RAW_ADDRESSES"]
        assert entries == [{"name": "NEXUS_LOG_RAW_ADDRESSES", "value": "1"}], owner

    masker = (REPO_ROOT / "ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/logging/"
              "LogAddressMasker.java").read_text(encoding="utf-8")
    assert 'RAW_ENV = "NEXUS_LOG_RAW_ADDRESSES"' in masker
    assert 'return "1".equals(env.apply(RAW_ENV));' in masker, "unset means masked"

    source = next(doc for doc in load_documents((MANIFEST_DIR / "50-service-deployment.yaml").read_text())
                  if doc["kind"] == "Deployment")
    candidate = preview.workload_manifest(source, "registry.example.invalid/candidate@sha256:" + "a" * 64,
                                          "192.0.2.10", {
                                              "ui2-compliance": "http://192.0.2.30:8085",
                                              "ui2-configuration": "http://192.0.2.40:8084",
                                          })
    container = next(c for c in candidate["spec"]["template"]["spec"]["containers"] if c["name"] == "service")
    assert [e for e in container["env"] if e["name"] == "NEXUS_LOG_RAW_ADDRESSES"] == [
        {"name": "NEXUS_LOG_RAW_ADDRESSES", "value": "0"}]


# ---------------------------------------------------------------------------
# Database reach protection, layer 2 (PO decision 2026-11-16)
# ---------------------------------------------------------------------------


def _pod_templates_with_labels():
    """(object name, pod labels, pod spec) for every pod template in deploy/ui2/*.yaml."""
    out = []
    for path in sorted(MANIFEST_DIR.glob("*.yaml")):
        for doc in load_documents(path.read_text(encoding="utf-8")):
            spec = doc.get("spec") or {}
            if doc.get("kind") == "CronJob":
                spec = spec["jobTemplate"]["spec"]
            template = spec.get("template") or {}
            if template.get("spec"):
                out.append((doc["metadata"]["name"], (template.get("metadata") or {}).get("labels") or {}, template["spec"]))
    return out


def _selector_matches(selector: dict, labels: dict) -> bool:
    for key, value in (selector.get("matchLabels") or {}).items():
        if labels.get(key) != value:
            return False
    for expr in selector.get("matchExpressions") or []:
        assert expr["operator"] == "In"
        if labels.get(expr["key"]) not in expr["values"]:
            return False
    return True


def test_database_ingress_policy_admits_exactly_the_workloads_with_a_jdbc_url():
    docs = load_documents((MANIFEST_DIR / "46-database-networkpolicy.yaml").read_text(encoding="utf-8"))
    (policy,) = docs
    assert policy["kind"] == "NetworkPolicy" and policy["metadata"]["name"] == "ui2-db-ingress"
    spec = policy["spec"]
    assert spec["policyTypes"] == ["Ingress"], "ingress on the database pod only; no pod is selected for egress"
    (rule,) = spec["ingress"]
    assert [(p["protocol"], p["port"]) for p in rule["ports"]] == [("TCP", 5432)]
    (source,) = rule["from"]
    assert set(source) == {"podSelector"}, "same namespace only: no namespaceSelector, no ipBlock"

    templates = _pod_templates_with_labels()
    database = [labels for name, labels, _ in templates if name == "ui2-db"]
    assert len(database) == 1 and _selector_matches(spec["podSelector"], database[0])

    def has_jdbc(pod_spec):
        return any("jdbc:postgresql" in str(env.get("value", ""))
                   for c in pod_spec.get("containers", []) + pod_spec.get("initContainers", [])
                   for env in c.get("env") or [])

    wants = {name for name, _, pod_spec in templates if has_jdbc(pod_spec)}
    admitted = {name for name, labels, _ in templates if _selector_matches(source["podSelector"], labels)}
    assert wants == {"ui2-service", "ui2-worker", "ui2-backup", "ui2-policy", "ui2-packet-analysis",
                     "ui2-script-runner", "ui2-flyway-bootstrap"}
    assert admitted == wants, "every database-using workload is admitted and no other pod is"


def test_the_new_network_policy_files_are_the_only_policy_change():
    existing = {"ui2-policy", "ui2-packet-analysis", "ui2-backup", "ui2-service-machine-port", "ui2-advisory-fetch",
                "ui2-script-runner", "ui2-script-run-default-deny"}
    names = {doc["metadata"]["name"] for doc in _all_docs() if doc.get("kind") == "NetworkPolicy"}
    assert names == existing | {"ui2-db-ingress"}
    # The optional external-database allow is a template: never globbed, never applied by the tooling, allow-only.
    template = MANIFEST_DIR / "external-db" / "46-database-egress-allow.yaml.tmpl"
    (external,) = load_documents(template.read_text(encoding="utf-8"))
    assert external["spec"]["policyTypes"] == ["Egress"]
    (rule,) = external["spec"]["egress"]
    assert [(p["protocol"], p["port"]) for p in rule["ports"]] == [("TCP", 5432)]
    assert rule["to"][0]["ipBlock"]["cidr"] == "EXTERNAL_DB_CIDR_SET_AT_DEPLOY", "address is never committed"
    admitted = {name for name, labels, _ in _pod_templates_with_labels() if _selector_matches(external["spec"]["podSelector"], labels)}
    assert admitted == {"ui2-service", "ui2-worker", "ui2-backup", "ui2-policy", "ui2-packet-analysis",
                        "ui2-script-runner", "ui2-flyway-bootstrap"}


def _script_runner_documents():
    text = (MANIFEST_DIR / "61-script-runner-deployment.yaml").read_text(encoding="utf-8")
    return [(doc["kind"], doc["metadata"]["name"], doc) for doc in load_documents(text)]


def test_script_runner_broker_manifest():
    """SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b): the broker runs the service image with its own database
    login and internal-port token from files, its service-account token for its Role, and no store key of any kind."""
    docs = {(kind, name): doc for kind, name, doc in _script_runner_documents()}
    assert set(docs) == {("ServiceAccount", "ui2-script-runner"), ("ServiceAccount", "ui2-script-run"),
                         ("Role", "ui2-script-runner"), ("RoleBinding", "ui2-script-runner"),
                         ("NetworkPolicy", "ui2-script-run-default-deny"), ("NetworkPolicy", "ui2-script-runner"),
                         ("Deployment", "ui2-script-runner")}
    assert docs[("ServiceAccount", "ui2-script-run")]["automountServiceAccountToken"] is False
    deployment = docs[("Deployment", "ui2-script-runner")]
    assert deployment["spec"]["replicas"] == 1 and deployment["spec"]["strategy"] == {"type": "Recreate"}
    pod = deployment["spec"]["template"]["spec"]
    assert pod["serviceAccountName"] == "ui2-script-runner" and pod["automountServiceAccountToken"] is True
    (container,) = pod["containers"]
    assert container["name"] == "script-runner" and container["args"] == ["ui2-script-runner"]
    assert re.fullmatch(r"registry\.kube-system\.svc\.cluster\.local/nexus-ui2-service@sha256:[0-9a-f]{64}",
                        container["image"]), "the broker runs the main service image (its Base64-intrinsic entrypoint)"
    assert container["securityContext"]["readOnlyRootFilesystem"] is True
    assert container["securityContext"]["capabilities"]["drop"] == ["ALL"]
    env = {e["name"]: e for e in container["env"]}
    # No store key and no shared database login: the service releases what a run needs (finding 1).
    for forbidden in ("UI2_ARTEFACT_STORE_KEY", "UI2_ARTEFACT_STORE_KEY_FILE", "UI2_CREDENTIAL_STORE_KEY",
                      "UI2_CREDENTIAL_STORE_KEY_FILE", "UI2_DB_USERNAME", "UI2_DB_PASSWORD", "UI2_ARTEFACT_STORE_ROOT"):
        assert forbidden not in env, forbidden
    assert not any("valueFrom" in e and "secretKeyRef" in e["valueFrom"] for e in container["env"])
    assert env["UI2_NODE_IPS"]["valueFrom"] == {"fieldRef": {"fieldPath": "status.hostIP"}}
    assert env["UI2_SCRIPT_EXEC_IMAGE"]["value"] == "set-by-module-deploy"
    secrets = {v["secret"]["secretName"] for v in pod["volumes"] if "secret" in v}
    assert secrets == {"ui2-db-script-runner", "ui2-script-runner-token"}
    assert not any("persistentVolumeClaim" in v or "hostPath" in v for v in pod["volumes"])


def test_script_runner_role_is_namespaced_and_minimal():
    """The broker's Role (finding 1/4 fix): only the verbs the broker uses, never get/list/watch on secrets, never
    exec, never a cluster-wide binding. RBAC cannot restrict create by name; ScriptRunKubernetes enforces the prefix."""
    docs = {(kind, name): doc for kind, name, doc in _script_runner_documents()}
    role = docs[("Role", "ui2-script-runner")]
    assert role["metadata"]["namespace"] == "ui2"
    rules = {(tuple(r["apiGroups"]), tuple(r["resources"])): set(r["verbs"]) for r in role["rules"]}
    assert rules == {
        (("batch",), ("jobs",)): {"create", "get", "list", "watch", "delete"},
        (("",), ("pods",)): {"get", "list", "watch"},
        (("",), ("pods/log",)): {"get"},
        (("",), ("secrets",)): {"create", "delete"},
        (("networking.k8s.io",), ("networkpolicies",)): {"create", "get", "delete"},
    }
    allowed = {"create", "get", "list", "watch", "delete"}
    assert all(verbs <= allowed for verbs in rules.values())
    binding = docs[("RoleBinding", "ui2-script-runner")]
    assert binding["roleRef"] == {"apiGroup": "rbac.authorization.k8s.io", "kind": "Role", "name": "ui2-script-runner"}
    assert binding["subjects"] == [{"kind": "ServiceAccount", "name": "ui2-script-runner", "namespace": "ui2"}]
    assert not any(doc.get("kind") in ("ClusterRole", "ClusterRoleBinding") for doc in _all_docs()
                   if "script" in doc.get("metadata", {}).get("name", ""))


def test_execution_pods_are_default_denied_and_never_reach_the_database():
    docs = {(kind, name): doc for kind, name, doc in _script_runner_documents()}
    deny = docs[("NetworkPolicy", "ui2-script-run-default-deny")]["spec"]
    assert deny["podSelector"] == {"matchLabels": {"app.kubernetes.io/component": "script-run"}}
    assert deny["policyTypes"] == ["Ingress", "Egress"] and deny["ingress"] == [] and deny["egress"] == []
    broker = docs[("NetworkPolicy", "ui2-script-runner")]["spec"]
    assert broker["policyTypes"] == ["Ingress"] and broker["ingress"] == []
    db = next(doc for doc in _all_docs() if doc.get("kind") == "NetworkPolicy" and doc["metadata"]["name"] == "ui2-db-ingress")
    assert not _selector_matches(db["spec"]["ingress"][0]["from"][0]["podSelector"],
                                 {"app.kubernetes.io/part-of": "nexus-ui2", "app.kubernetes.io/component": "script-run"})
    machine = next(doc for doc in _all_docs() if doc.get("kind") == "NetworkPolicy"
                   and doc["metadata"]["name"] == "ui2-service-machine-port")
    froms = [rule.get("from", [{}])[0].get("podSelector", {}).get("matchLabels", {}) for rule in machine["spec"]["ingress"]]
    assert {"app.kubernetes.io/component": "script-runner"} in froms
    assert {"app.kubernetes.io/component": "script-run"} not in froms, "execution pods never reach port 8086"


def test_no_secret_key_ref_reaches_a_script_execution_pod():
    """Review finding 1: the execution pod spec is generated in Java (ScriptRunPodSpec, asserted there); no committed
    manifest declares a script-run pod, and the broker's own pod carries no secretKeyRef either."""
    for kind, name, doc in _script_runner_documents():
        assert "secretKeyRef" not in json.dumps(doc), name
    source = (REPO_ROOT / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/scriptrunner/ScriptRunPodSpec.java"
              ).read_text(encoding="utf-8")
    for key in ('"secretKeyRef"', '"envFrom"', '"valueFrom"', '"configMapKeyRef"'):
        assert key not in source, key
    assert '"automountServiceAccountToken", false' in source


def test_service_carries_the_broker_token_digest_and_a_writable_script_store():
    service = next(doc for doc in _all_docs() if doc.get("kind") == "Deployment" and doc["metadata"]["name"] == "ui2-service")
    (container,) = [c for c in service["spec"]["template"]["spec"]["containers"] if c["name"] == "service"] or \
        service["spec"]["template"]["spec"]["containers"][:1]
    env = {e["name"]: e for e in container["env"]}
    ref = env["UI2_SCRIPT_RUNNER_TOKEN_SHA256"]["valueFrom"]["secretKeyRef"]
    assert ref == {"name": "ui2-script-runner-token", "key": "sha256", "optional": True}
    assert env["UI2_SCRIPT_STORE_ROOT"]["value"].startswith(env["UI2_REPORT_STORE_ROOT"]["value"] + "/")
    assert env["UI2_SCRIPT_SCHEDULE_ZONE"]["value"] == "Europe/Istanbul"


def test_script_exec_containerfile_and_image_builder():
    """Amendment 2026-11-18 (b) item 8: the execution image is the main image by digest plus the section 7
    interpreters, no PowerShell (refused at upload instead), built like the Packet Analysis image."""
    sys.path.insert(0, str(REPO_ROOT / "tools" / "delivery"))
    import hosta_script_exec_image as sx

    cf = (REPO_ROOT / "ui2" / "Containerfile.script-exec").read_text(encoding="utf-8")
    for token in ("ARG SERVICE_IMAGE", "FROM ${SERVICE_IMAGE}", "nodejs:22", "python3.12", "requests==", "paramiko==",
                  "pyyaml==", "perl-interpreter", "ruby", "USER 20185", "chmod 0644 /app/service.jar"):
        assert token in cf, token
    assert "pwsh" not in cf.replace("PowerShell 7 is NOT installed", "").split("# Built by")[1]
    assert not (REPO_ROOT / "ui2" / "Containerfile.script-runner").exists()
    commit = "a" * 40
    base = sx.REPOSITORY + "@sha256:" + "1" * 64
    args = sx.builder_args(["--context=dir:///workspace", "--dockerfile=/x"], commit, base)
    assert "--dockerfile=/workspace/ui2/Containerfile.script-exec" in args and "--dockerfile=/x" not in args
    assert "--build-arg=SERVICE_IMAGE=" + base in args
    assert "--destination=" + sx.REPOSITORY + ":" + commit[:12] + "-sx" in args
    with pytest.raises(ValueError):
        sx.service_image(sx.REPOSITORY + ":latest")


def test_run_build_builds_scans_and_hands_over_the_script_exec_image():
    text = (REPO_ROOT / "deploy/ui2-image-build/run_build.sh").read_text(encoding="utf-8")
    build = text.index('hosta_script_exec_image.py" build')
    gate = text.index("security_host.py\" gate")
    rollout = text.index('module_deploy.py" --targets')
    assert build < gate < rollout
    assert '${SX_IMAGE:+--image "$SX_IMAGE"}' in text
    assert '${SX_IMAGE:+--script-exec-image "$SX_IMAGE"}' in text
    assert "script-runner" in text.split("NEXUS_DEPLOY_TARGETS=")[1].split("\n")[0]


def test_run_build_leaves_script_runner_out_when_its_image_fails():
    """2026-11-20: a UBI CDN outage failed the execution image and stopped whole releases; the image is optional."""
    text = (REPO_ROOT / "deploy/ui2-image-build/run_build.sh").read_text(encoding="utf-8")
    assert 'if SX_IMAGE="$(python3 "$REPO/tools/delivery/hosta_script_exec_image.py" build' in text
    assert "SX_FAILED=1" in text
    drop = text.index('if [ "${SX_FAILED:-0}" = 1 ]; then')
    assert drop < text.index('module_deploy.py" --targets')
    assert '[ "$target" = script-runner ] ||' in text


def test_no_worker_deployment_mounts_or_references_migrate_credentials():
    """DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN §3.2: worker Deployments never run Flyway, never expose
    UI2_DB_MIGRATE_USER_FILE / UI2_DB_MIGRATE_PASSWORD_FILE, and project only app-user / app-password from ui2-db."""
    worker_deployments = {
        "Deployment/ui2-worker",
        "Deployment/ui2-backup",
        "Deployment/ui2-policy",
        "Deployment/ui2-packet-analysis",
        "Deployment/ui2-configuration",
        "Deployment/ui2-compliance",
    }
    seen = set()
    for _, owner, spec in _pod_specs():
        if owner not in worker_deployments:
            continue
        seen.add(owner)
        for container in spec.get("containers") or []:
            env_names = {entry.get("name") for entry in container.get("env") or []}
            assert "UI2_DB_MIGRATE_USER_FILE" not in env_names, f"{owner}: must not expose UI2_DB_MIGRATE_USER_FILE"
            assert "UI2_DB_MIGRATE_PASSWORD_FILE" not in env_names, f"{owner}: must not expose UI2_DB_MIGRATE_PASSWORD_FILE"
        for volume in spec.get("volumes") or []:
            secret = volume.get("secret")
            if not secret or secret.get("secretName") != "ui2-db":
                continue
            items = secret.get("items")
            assert items == [
                {"key": "app-user", "path": "app-user"},
                {"key": "app-password", "path": "app-password"},
            ], f"{owner}: db-credentials volume must project only app-user and app-password via items"
    assert seen == worker_deployments

