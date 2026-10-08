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
        "Job/ui2-e2e", "CronJob/ui2-e2e",
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
    assert kinds.count("PersistentVolumeClaim") == 2, (
        "§5.2 plus BK-17: the database data claim and the worker-only recovery volume claim "
        "(NXS-LOCAL-0170: 35-artefact-store-pvc.yaml), and never a third"
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
WORKER_OWNER = "Deployment/ui2-worker"


def _is_push_inbox(owner: str, volume: dict) -> bool:
    host = volume.get("hostPath")
    return (isinstance(host, dict) and owner == WORKER_OWNER and bool(PUSH_INBOX_PATH.match(str(host.get("path", ""))))
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
            assert path.name == "52-worker-deployment.yaml", f"{path.name}: hostPath outside the worker Deployment (OS-3a.3)"


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
                if owner == "Deployment/ui2-policy:policy" and section == "limits" and resource == "cpu":
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
    assert pvc_names == {"ui2-db-data", "ui2-artefact-store"}, (
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
        if owner.startswith("Deployment/ui2-worker") or owner == "Deployment/ui2-policy":
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


def test_the_runtime_removes_all_python_rpms_offline_and_verifies_in_the_same_run():
    text = CONTAINERFILE.read_text(encoding="utf-8")
    runtime = text.rsplit("\nFROM ", 1)[1]
    removal = "RUN rpm -e --nodeps $(rpm -qa --qf '%{NAME}\\n' 'python3*')"
    verification = ' && test -z "$(rpm -qa \'python3*\')"'
    assert removal + " \\\n" + verification in runtime
    assert runtime.index("USER 0\n") < runtime.index(removal) < runtime.index("USER 185\n")
    assert not re.search(r"\b(?:dnf|microdnf|yum)\b", runtime)
    entries = yaml.safe_load((REPO_ROOT / "security/baseline.yaml").read_text())["accepted"]
    assert not {"CVE-2026-19553", "CVE-2026-57585"} & {entry["rule"] for entry in entries}


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
    tls = [entry for entry in entries if entry["rule"] == "weak-ssl-context"]
    # PAN (PO_DECISION_RECORD_2026_09_21) and the appliance pinning trust manager
    # (PO_DECISION_RECORD_2026_09_30_HTTPS_APPLIANCE_CERTIFICATE_PINNING).
    assert sorted(entry["location"].rsplit("/", 1)[1] for entry in tls) == [
        "DirectoryTrustPolicy.java:155", "PanXmlApiTransport.java:286", "SystemStatusService.java:207"
    ]


def test_gitleaks_allowlists_only_the_approved_commit_and_seven_exact_test_paths():
    config = tomllib.loads((REPO_ROOT / ".gitleaks.toml").read_text())
    assert config["extend"] == {"useDefault": True}
    assert "rules" not in config, "Keep the default detection rules intact"
    synthetic, history, fixtures = config["allowlists"]
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
    assert history["commits"] == ["8288cb4cd61178d049d348da39d95816f5e93852"]
    assert history["targetRules"] == ["generic-api-key"]
    assert not {"paths", "regexes", "stopwords"} & history.keys()
    assert fixtures["targetRules"] == ["generic-api-key"]
    assert not {"commits", "regexes", "stopwords"} & fixtures.keys()
    entries = yaml.safe_load((REPO_ROOT / "security/baseline.yaml").read_text())["accepted"]
    paths = {entry["location"] for entry in entries if entry["rule"] == "generic-api-key" and "commit" not in entry}
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
    assert env["GRADLE_USER_HOME"] == "/workspace/ui2/.gradle-home"
    assert spec["automountServiceAccountToken"] is False
    assert any(v.get("configMap") == {"name": "corp-ca"} for v in spec["volumes"])
    for container in (postgres, runner):
        assert container["resources"]["requests"] and container["resources"]["limits"]
