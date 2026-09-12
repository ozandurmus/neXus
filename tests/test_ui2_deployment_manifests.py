"""B1-1c construction-rule check for the UI 2.0 container image and manifests.

`docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
(FROZEN) §5.1 states ten construction rules, `OS-1` to `OS-10`, and §3 states
the image rules `FS-1` to `FS-7` and `EP-1` to `EP-4`. The rules exist because
the corporate platform's `restricted-v2` security context constraint enforces
them and the local cluster enforces none of them (`PORT-4`): a constraint that
is only satisfied where it is enforced is discovered at the stage where a
refused admission is least recoverable.

This module is the check that keeps them satisfied here. It fails when a
manifest violates a named rule, which is the point — a green run is evidence
that `deploy/ui2/` is still admissible under `restricted-v2` without
amendment.

It parses the manifests itself rather than importing a YAML library, because
the repository's test interpreter carries none and this movement does not add
a dependency to make a check possible. The parser covers the block subset the
manifest set actually uses and raises on anything outside it, so an
unparsable construct fails the suite instead of being silently skipped.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[1]
CONTAINERFILE = REPO_ROOT / "ui2" / "Containerfile"
MANIFEST_DIR = REPO_ROOT / "deploy" / "ui2"
OPENSHIFT_DIR = MANIFEST_DIR / "openshift"

# §11 check 3 (BP-2): no host container tool may appear in any tracked file
# of the image or manifest path. The build runs inside the cluster.
HOST_CONTAINER_TOOL = re.compile(r"docker |podman |buildah|nerdctl|/var/run/docker\.sock")

# §11 check 4: OS-2, OS-3, OS-4, OS-5, OS-9 and OS-10 as a single grep, which
# is how the contract states the check.
FORBIDDEN_CONSTRUCT = re.compile(
    r"runAsUser|runAsGroup|fsGroup|hostPath|hostNetwork|hostPID|hostIPC"
    r"|hostPort|privileged: true|NodePort|LoadBalancer|:latest"
)


# ---------------------------------------------------------------------------
# A small block-YAML reader for the subset the manifest set uses.
# ---------------------------------------------------------------------------


class ManifestSyntaxError(AssertionError):
    """Raised when a manifest uses a construct this reader does not cover."""


_FLOW_SEQ = re.compile(r"^\[(.*)\]$")


def _indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _next_content(lines: list[str], i: int) -> int:
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped and not stripped.startswith("#"):
            return i
        i += 1
    return len(lines)


def _strip_inline_comment(value: str) -> str:
    if value[:1] in ('"', "'"):
        quote = value[0]
        end = value.find(quote, 1)
        if end == -1:
            raise ManifestSyntaxError(f"unterminated quoted scalar: {value!r}")
        return value[: end + 1]
    cut = value.find(" #")
    return value if cut == -1 else value[:cut].rstrip()


def _scalar(value: str):
    value = _strip_inline_comment(value).strip()
    flow = _FLOW_SEQ.match(value)
    if flow:
        inner = flow.group(1).strip()
        if not inner:
            return []
        return [_scalar(item.strip()) for item in inner.split(",")]
    if value[:1] in ('"', "'") and value[-1:] == value[:1] and len(value) >= 2:
        return value[1:-1]
    if value in ("true", "True"):
        return True
    if value in ("false", "False"):
        return False
    if value in ("null", "~", ""):
        return None
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    return value


def _read_block_scalar(lines: list[str], i: int, parent_indent: int) -> tuple[str, int]:
    body: list[str] = []
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            body.append("")
            i += 1
            continue
        if _indent_of(line) <= parent_indent:
            break
        body.append(line)
        i += 1
    while body and not body[-1]:
        body.pop()
    if not body:
        return "", i
    pad = min(_indent_of(line) for line in body if line)
    return "\n".join(line[pad:] if line else "" for line in body), i


def _parse_mapping(lines: list[str], i: int, indent: int) -> tuple[dict, int]:
    result: dict = {}
    while True:
        i = _next_content(lines, i)
        if i >= len(lines):
            break
        line = lines[i]
        if _indent_of(line) != indent:
            break
        stripped = line.strip()
        if stripped.startswith("- ") or stripped == "-" or stripped.startswith("---"):
            break
        if ":" not in stripped:
            raise ManifestSyntaxError(f"not a mapping entry: {stripped!r}")
        key, _, rest = stripped.partition(":")
        key = key.strip()
        rest = rest.strip()
        if rest in ("|", "|-", "|+", ">", ">-"):
            result[key], i = _read_block_scalar(lines, i + 1, indent)
        elif rest:
            result[key] = _scalar(rest)
            i += 1
        else:
            nxt = _next_content(lines, i + 1)
            if nxt >= len(lines):
                result[key] = None
                i = nxt
                continue
            child_indent = _indent_of(lines[nxt])
            child_stripped = lines[nxt].strip()
            is_seq = child_stripped == "-" or child_stripped.startswith("- ")
            if is_seq and child_indent >= indent:
                result[key], i = _parse_sequence(lines, nxt, child_indent)
            elif child_indent > indent:
                result[key], i = _parse_mapping(lines, nxt, child_indent)
            else:
                result[key] = None
                i = nxt
    return result, i


def _parse_sequence(lines: list[str], i: int, indent: int) -> tuple[list, int]:
    items: list = []
    while True:
        i = _next_content(lines, i)
        if i >= len(lines):
            break
        line = lines[i]
        stripped = line.strip()
        if _indent_of(line) != indent or not (stripped == "-" or stripped.startswith("- ")):
            break
        body = stripped[2:] if stripped.startswith("- ") else ""
        if not body:
            nxt = _next_content(lines, i + 1)
            if nxt >= len(lines) or _indent_of(lines[nxt]) <= indent:
                raise ManifestSyntaxError(f"empty sequence entry at line {i + 1}")
            item, i = _parse_mapping(lines, nxt, _indent_of(lines[nxt]))
            items.append(item)
            continue
        if ":" in _strip_inline_comment(body) and not body.startswith(("[", '"', "'")):
            # "- key: value" starts a mapping whose first key sits two
            # columns right of the dash. Rewriting the dash keeps the reader
            # to one code path for mappings.
            lines[i] = " " * (indent + 2) + body
            item, i = _parse_mapping(lines, i, indent + 2)
            items.append(item)
            continue
        items.append(_scalar(body))
        i += 1
    return items, i


def load_documents(text: str) -> list[dict]:
    """Parse one manifest file into its documents."""
    docs: list[dict] = []
    for chunk in re.split(r"^---\s*$", text, flags=re.MULTILINE):
        lines = chunk.split("\n")
        start = _next_content(lines, 0)
        if start >= len(lines):
            continue
        doc, _ = _parse_mapping(lines, start, _indent_of(lines[start]))
        if doc:
            docs.append(doc)
    return docs


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


def _pod_specs() -> list[tuple[Path, str, dict]]:
    """Every pod template in the set, with the file and object it came from."""
    found: list[tuple[Path, str, dict]] = []
    for path, doc in _documents():
        name = f"{doc.get('kind')}/{(doc.get('metadata') or {}).get('name')}"
        if doc.get("kind") == "Pod":
            found.append((path, name, doc.get("spec") or {}))
            continue
        template = ((doc.get("spec") or {}).get("template") or {}).get("spec")
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


def test_the_rule_checks_below_have_something_to_check():
    """A rule asserted over an empty list passes without proving anything.

    Every per-container check below iterates what the reader found, so this
    test is what stops a reader that silently degraded to nothing from
    turning the whole module green.
    """
    pod_specs = _pod_specs()
    assert len(pod_specs) == 2, (
        f"expected the service and the database workloads, found {[o for _, o, _ in pod_specs]}"
    )
    containers = _containers()
    assert [owner for _, owner, _ in containers] == [
        "StatefulSet/ui2-db:database",
        "Deployment/ui2-service:service",
    ], f"unexpected container set: {[owner for _, owner, _ in containers]}"


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
    assert kinds.count("Service") == 2, "§5.2: one Service for the service, one for the database"


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


def test_no_pod_spec_requests_a_fixed_identity():
    """FS-7 / OS-2, asserted on the parsed objects and not only on the text."""
    for path, owner, spec in _pod_specs():
        security = spec.get("securityContext") or {}
        for field in ("runAsUser", "runAsGroup", "fsGroup"):
            assert field not in security, f"{path.name}: {owner} sets {field}"
    for path, owner, container in _containers():
        security = container.get("securityContext") or {}
        for field in ("runAsUser", "runAsGroup"):
            assert field not in security, f"{path.name}: {owner} sets {field}"


def test_every_container_runs_non_root():
    """OS-1."""
    for path, owner, container in _containers():
        security = container.get("securityContext") or {}
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
        seccomp = security.get("seccompProfile") or {}
        assert seccomp.get("type") == "RuntimeDefault", (
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
                assert values.get(resource) is not None, (
                    f"{path.name}: {owner} declares no {section}.{resource}"
                )


def test_every_writable_path_is_supplied_as_a_mount():
    """FS-4: with a read-only root filesystem, a written path is a mount."""
    for path, owner, spec in _pod_specs():
        volumes = {volume.get("name"): volume for volume in spec.get("volumes") or []}
        for container in spec.get("containers") or []:
            for mount in container.get("volumeMounts") or []:
                name = mount.get("name")
                assert name in volumes, f"{path.name}: {owner} mounts unknown volume {name!r}"
                volume = volumes[name]
                assert "hostPath" not in volume, f"{path.name}: {owner} mounts a node path"
                assert set(volume) & {"emptyDir", "persistentVolumeClaim", "configMap", "secret"}, (
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


def _containerfile_declared_writable_paths() -> set[str]:
    text = CONTAINERFILE.read_text(encoding="utf-8")
    match = re.search(r"chmod 0770 ([^\n\\]+)", text)
    assert match, "ui2/Containerfile no longer declares its writable set"
    return set(match.group(1).split())


def test_every_image_reference_is_immutable():
    """OS-10 / BP-7."""
    for path, owner, container in _containers():
        image = container.get("image")
        assert isinstance(image, str) and image, f"{path.name}: {owner} has no image"
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
    assert len(froms) == 2, f"§3.2: the image is two stages, found {len(froms)}"
    for line in froms:
        assert "@sha256:" in line, f"§3.1: base image is not pinned by digest: {line}"


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
