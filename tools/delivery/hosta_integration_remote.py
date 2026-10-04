"""Host-side orchestration only; tests and XML parsing run in the Job."""
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
from urllib.parse import urlsplit


def kubectl(*args, data=None):
    return subprocess.check_output(["kubectl", *args], input=data, stderr=subprocess.DEVNULL, timeout=60)


def run(name):
    if not re.fullmatch(r"ui2-integration-[a-f0-9]{12}", name):
        raise ValueError("invalid run name")
    os.chdir(Path.home() / "nexus")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if json.loads(Path("project/deploy_info.json").read_text())["commit"] != commit:
        raise ValueError("checkout does not match deployment marker")
    manifest = subprocess.check_output(["git", "show", f"{commit}:deploy/ui2-image-build/33-integration-postgres.yaml"])
    job = json.loads(kubectl("create", "--dry-run=client", "-f", "-", "-o", "json", data=manifest.replace(b"INTEGRATION_RUN", name.encode())))
    build = json.loads(kubectl("create", "--dry-run=client", "-f", str(Path.home() / "build-job-proxy.yaml"), "-o", "json"))
    env = build["spec"]["template"]["spec"]["containers"][0]["env"]
    proxy_env = [e for e in env if e["name"] in ("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "GRADLE_OPTS")]
    values = {e["name"]: e["value"] for e in proxy_env}
    if not all(values.get(k) for k in ("HTTP_PROXY", "HTTPS_PROXY", "GRADLE_OPTS")):
        raise ValueError("missing build proxy configuration")
    destinations = []
    for key in ("HTTP_PROXY", "HTTPS_PROXY"):
        proxy = urlsplit(values[key])
        if not (proxy.scheme == "http" and proxy.hostname and proxy.port and not proxy.username and not proxy.password):
            raise ValueError("unsupported build proxy configuration")
        for address in sorted({r[4][0] for r in socket.getaddrinfo(proxy.hostname, proxy.port, type=socket.SOCK_STREAM)}):
            ip = ipaddress.ip_address(address)
            if ip.is_loopback or ip.is_unspecified:
                raise ValueError("invalid proxy destination")
            destinations.append({"to": [{"ipBlock": {"cidr": f"{ip}/{ip.max_prefixlen}"}}],
                                 "ports": [{"protocol": "TCP", "port": proxy.port}]})
    policy = {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
              "metadata": {"name": name, "namespace": "ui2-build"},
              "spec": {"podSelector": {"matchLabels": {"nexus-integration-run": name}},
                       "policyTypes": ["Ingress", "Egress"], "ingress": [], "egress": destinations + [
                           {"to": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
                                    "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}],
                            "ports": [{"protocol": p, "port": 53} for p in ("UDP", "TCP")]}]}}
    job["spec"]["template"]["spec"]["containers"][0]["env"].extend(proxy_env)
    kubectl("apply", "-f", "-", data=json.dumps(policy).encode())
    kubectl("apply", "-f", "-", data=json.dumps(job).encode())
    deadline = time.monotonic() + 1800
    pod = None
    while time.monotonic() < deadline:
        pods = json.loads(kubectl("-n", "ui2-build", "get", "pods", "-l", f"nexus-integration-run={name}", "-o", "json"))["items"]
        if pods and any(c["name"] == "runner" and "running" in c["state"] for c in pods[0].get("status", {}).get("containerStatuses", [])):
            pod = pods[0]["metadata"]["name"]
            break
        time.sleep(2)
    if not pod:
        raise TimeoutError()
    # Only committed source crosses into the emptyDir; no host-local config/cache/secret.
    with subprocess.Popen(["git", "archive", commit, "ui2", "tools/delivery/integration_runner.sh", "tools/delivery/IntegrationSummary.java"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as archive:
        subprocess.run(["kubectl", "-n", "ui2-build", "exec", "-i", pod, "-c", "runner", "--", "tar", "-xf", "-", "-C", "/workspace"], stdin=archive.stdout, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=max(1, deadline - time.monotonic()))
        archive.stdout.close()
        if archive.wait(timeout=60) != 0:
            raise RuntimeError("source archive failed")
    kubectl("-n", "ui2-build", "exec", pod, "-c", "runner", "--", "touch", "/workspace/ready")
    while time.monotonic() < deadline:
        state = json.loads(kubectl("-n", "ui2-build", "get", "job", name, "-o", "json"))
        conditions = {c["type"] for c in state.get("status", {}).get("conditions", []) if c["status"] == "True"}
        if conditions & {"Complete", "Failed"}:
            logs = kubectl("-n", "ui2-build", "logs", pod, "-c", "runner").decode()
            summaries = re.findall(r"^INTEGRATION: (?:PASS \(tests=[1-9][0-9]*; skipped=0\)|FAIL \(tests=[0-9]+; failures=[0-9]+; errors=[0-9]+; skipped=[0-9]+\))$", logs, re.M)
            if len(summaries) != 1:
                raise ValueError("missing or duplicate summary")
            summary = summaries[0]
            if summary.startswith("INTEGRATION: PASS") and "Complete" not in conditions:
                raise ValueError("summary contradicts Job status")
            print(summary)
            for line in re.findall(r"^FAILED [A-Za-z_$][A-Za-z0-9_$]*\.[A-Za-z_$][A-Za-z0-9_$]*$", logs, re.M)[:20]:
                print(line)
            return 0 if "Complete" in conditions and summary.startswith("INTEGRATION: PASS") else 1
        time.sleep(2)
    raise TimeoutError()


if __name__ == "__main__":
    try:
        sys.exit(run(sys.argv[1]))
    except Exception:
        print("INTEGRATION: ERROR (runner/setup failed; raw output withheld)")
        sys.exit(2)
