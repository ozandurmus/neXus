#!/usr/bin/env python3
"""Mirror reviewed scanner digests with the existing Kaniko build mechanism (host operator only)."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

KANIKO = "gcr.io/kaniko-project/executor@sha256:4e7a52dd1f14872430652bb3b027405b8dfd17c4538751c620ac005741ef9698"
REGISTRY = "registry.kube-system.svc.cluster.local"


def command(*argv, **kwargs):
    p = subprocess.run(list(argv), capture_output=True, timeout=180, **kwargs)
    if p.returncode:
        raise RuntimeError("mirror command failed")
    return p.stdout


def mirror(tool, source, proxy):
    if tool not in ("semgrep", "gitleaks", "trivy", "zap") or not re.fullmatch(r"[a-zA-Z0-9./_-]+@sha256:[a-f0-9]{64}", source):
        raise ValueError("a reviewed source digest is required")
    name = "security-mirror-" + tool
    destination = REGISTRY + "/security/" + tool
    cm = dict(apiVersion="v1", kind="ConfigMap", metadata={"name": name, "namespace": "ui2-build"},
              data={"Containerfile": "FROM " + source + "\n"})
    command("kubectl", "apply", "-f", "-", input=json.dumps(cm).encode())
    command("kubectl", "-n", "ui2-build", "delete", "job", name, "--ignore-not-found", "--wait=true")
    # Same builder, namespace and explicit root/writable-filesystem exception as ui2-image-build.
    pod = {"restartPolicy": "Never", "automountServiceAccountToken": False,
           "securityContext": {"seccompProfile": {"type": "RuntimeDefault"}},
           "containers": [{"name": "builder", "image": KANIKO,
               "securityContext": {"allowPrivilegeEscalation": False},
               "args": ["--context=dir:///context", "--dockerfile=/context/Containerfile", "--cache=false",
                        "--destination=" + destination + ":" + source[-12:], "--insecure",
                        "--digest-file=/dev/termination-log"],
               "env": [{"name": n, "value": proxy} for n in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")]
                      + [{"name": "NO_PROXY", "value": REGISTRY}, {"name": "no_proxy", "value": REGISTRY}],
               "resources": {"requests": {"cpu": "100m", "memory": "256Mi"}, "limits": {"cpu": "2", "memory": "4Gi"}},
               "volumeMounts": [{"name": "context", "mountPath": "/context", "readOnly": True}]}],
           "volumes": [{"name": "context", "configMap": {"name": name}}]}
    obj = dict(apiVersion="batch/v1", kind="Job", metadata={"name": name, "namespace": "ui2-build"},
               spec={"backoffLimit": 0, "activeDeadlineSeconds": 1800, "template": {"spec": pod}})
    command("kubectl", "create", "-f", "-", input=json.dumps(obj).encode())
    for _ in range(360):
        status = json.loads(command("kubectl", "-n", "ui2-build", "get", "job", name, "-o", "json"))["status"]
        conditions = {c["type"] for c in status.get("conditions", []) if c.get("status") == "True"}
        if "Failed" in conditions:
            raise RuntimeError("scanner mirror failed")
        if "Complete" in conditions:
            pods = json.loads(command("kubectl", "-n", "ui2-build", "get", "pods", "-l", "job-name=" + name, "-o", "json"))
            digest = pods["items"][-1]["status"]["containerStatuses"][0]["state"]["terminated"]["message"].strip()
            if not re.fullmatch(r"sha256:[a-f0-9]{64}", digest):
                raise RuntimeError("mirror did not return a digest")
            return destination + "@" + digest
        time.sleep(5)
    raise RuntimeError("scanner mirror timed out")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sources", type=Path, required=True, help="JSON object: tool -> reviewed upstream image@sha256")
    p.add_argument("--config", type=Path, required=True, help="host-local config; images are replaced atomically")
    args = p.parse_args()
    try:
        from security_manifests import settings
        config = json.loads(args.config.read_text())
        sources = json.loads(args.sources.read_text())
        if set(sources) != {"semgrep", "gitleaks", "trivy", "zap"}:
            raise ValueError("all four scanners required")
        import ipaddress
        ipaddress.IPv4Address(config["proxy_ip"])
        if type(config["proxy_port"]) is not int or not 1 <= config["proxy_port"] <= 65535:
            raise ValueError("invalid proxy port")
        proxy = f'http://{config["proxy_ip"]}:{config["proxy_port"]}'
        config["images"] = {tool: mirror(tool, source, proxy) for tool, source in sources.items()}
        settings(config)
        temporary = args.config.with_suffix(".tmp")
        temporary.write_text(json.dumps(config, indent=2) + "\n")
        temporary.chmod(0o600)
        temporary.replace(args.config)
        print("Scanner images mirrored; host-local digest lock updated.")
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError):
        raise SystemExit("Scanner mirror failed; no deployment configuration published.")
