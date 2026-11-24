#!/usr/bin/env python3
"""Render Kubernetes JSON (also YAML) using reviewed, locally mirrored image digests."""
import argparse
import copy
import ipaddress
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = "registry.kube-system.svc.cluster.local"
SERVICE = "ui2-service-internal.ui2.svc.cluster.local"
NS = "ui2-security"
LABEL = {"app.kubernetes.io/component": "security-scan"}
HARDENING = {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
             "capabilities": {"drop": ["ALL"]}}


def settings(config):
    for tool in ("semgrep", "gitleaks", "trivy", "zap"):
        if not re.fullmatch(re.escape(REGISTRY) + r"/[a-z0-9/_-]+@sha256:[a-f0-9]{64}", config["images"][tool]):
            raise ValueError("scanner image must be mirrored and digest pinned")
    for key in ("proxy_ip", "registry_ip", "service_ip"):
        ipaddress.IPv4Address(config[key])
    if type(config["proxy_port"]) is not int or not 1 <= config["proxy_port"] <= 65535:
        raise ValueError("invalid proxy port")
    if not config["node_name"] or "REPLACE" in config["node_name"]:
        raise ValueError("node name required for local reports")
    # Optional, opt-in: a persistent Trivy cache claim. It must be applied (deploy/security/trivy-cache-pvc.yaml)
    # BEFORE this key is set, otherwise every Job that mounts it would be stuck Pending.
    claim = config.get("trivy_cache_claim")
    if claim is not None and not (isinstance(claim, str) and re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", claim)):
        raise ValueError("invalid trivy cache claim")
    return config


def container(config, name, tool, command, mode):
    proxy = f'http://{config["proxy_ip"]}:{config["proxy_port"]}'
    env = {"SCAN_MODE": mode, "TZ": "Europe/Istanbul", "HOME": "/work/home", "PYTHONDONTWRITEBYTECODE": "1",
           "HTTP_PROXY": proxy, "HTTPS_PROXY": proxy, "http_proxy": proxy, "https_proxy": proxy,
           "NO_PROXY": f"localhost,127.0.0.1,{REGISTRY},{SERVICE}",
           "no_proxy": f"localhost,127.0.0.1,{REGISTRY},{SERVICE}"}
    if config.get("trivy_cache_claim"):
        # Only a shared (claimed) cache needs the lock; the per-Job emptyDir cache never does (2026-11-15).
        env["TRIVY_SHARED_CACHE"] = "1"
    return dict(name=name, image=config["images"][tool], command=command,
                securityContext=copy.deepcopy(HARDENING), env=[dict(name=k, value=v) for k, v in env.items()],
                resources={"requests": {"cpu": "100m", "memory": "256Mi"},
                           "limits": {"cpu": "2", "memory": "4Gi"}},
                volumeMounts=[dict(name="scripts", mountPath="/tools/security", readOnly=True),
                              dict(name="source", mountPath="/source", readOnly=True),
                              dict(name="source", mountPath="/rules", subPath="rules", readOnly=True),
                              dict(name="work", mountPath="/work"), dict(name="tmp", mountPath="/tmp"),
                              dict(name="cache", mountPath="/cache"),
                              *([dict(name="trivy-cache", mountPath="/cache/trivy")] if config.get("trivy_cache_claim") else []),
                              dict(name="reports", mountPath="/var/lib/nexus-security")])


PREGATE_TOOLS = re.compile(r"pregate-tools/[a-f0-9]{64}")


# Source stages whose result is fully determined by the source TREE (and the gate policy); see source_reused below.
TREE_STAGES = ("semgrep", "gitleaks", "trivy")


def job(config, mode="daily", image=None, commit=None, gate_name=None, pregate_tools=None, await_timeout=None,
        deadline=3600, source_reused=False):
    """`pregate_tools` (pre-merge gate, PO 2026-11-23): the PVC sub-path of the DEPLOYED scanner scripts and offline
    rules staged by security_host.snapshot(publish=False), content-addressed. The candidate commit is scanned, but never
    by its own tools/security.

    `source_reused` (deploy gate only): a pre-merge PASS of the same tree and policy covers the tree-determined stages
    (TREE_STAGES), so this Job runs only the Git history scan and the image scans of every digest the deploy rolls out."""
    settings(config)
    if pregate_tools is not None and (not gate_name or not PREGATE_TOOLS.fullmatch(pregate_tools)):
        raise ValueError("invalid pre-merge gate tools")
    if source_reused and (mode != "gate" or not gate_name or pregate_tools is not None):
        raise ValueError("source reuse is for the split deploy gate only")
    if type(deadline) is not int or not 600 <= deadline <= 4 * 3600:
        raise ValueError("invalid gate deadline")
    if await_timeout is not None and (type(await_timeout) is not int or not 60 <= await_timeout < deadline):
        raise ValueError("invalid image wait")
    prepare = container(config, "prepare", "semgrep", ["python3", "/tools/security/security_run.py", "prepare"], mode)
    if mode == "gate":
        images = [image] if isinstance(image, str) else list(image or ())
        if not gate_name and (not images or len(set(images)) != len(images) or not all(
                re.fullmatch(re.escape(REGISTRY) + r"/[a-z0-9/_-]+@sha256:[a-f0-9]{64}", i) for i in images)):
            raise ValueError("gate requires the freshly built digest")
        if not commit or not re.fullmatch(r"[a-f0-9]{40}", commit):
            raise ValueError("gate requires the built commit")
        prepare["env"] += [dict(name="EXPECTED_COMMIT", value=commit)]
        if gate_name:
            if not re.fullmatch(r"security-gate-[a-f0-9]{12}", gate_name):
                raise ValueError("invalid gate name")
            prepare["env"].append(dict(name="WAIT_FOR_IMAGE", value="1"))
            if pregate_tools:
                # The candidate snapshot is staged, not published: prepare reads it by name, never via `current`.
                prepare["env"].append(dict(name="SOURCE_COMMIT", value=commit))
        else:
            # Space-separated: every image the build produced is scanned in this one gate.
            prepare["env"].append(dict(name="GATE_IMAGE", value=" ".join(images)))
    stages = [prepare]
    if mode == "dast":
        zap = container(config, "zap", "zap", ["python3", "/tools/security/security_dast.py"], mode)
        zap["volumeMounts"] = [m for m in zap["volumeMounts"] if m["name"] != "reports"]
        zap["volumeMounts"] += [dict(name="work", mountPath="/zap/wrk"),
                                  dict(name="machine", mountPath="/run/machine", readOnly=True)]
        stages.append(zap)
    else:
        if source_reused:
            prepare["env"].append(dict(name="TREE_SCANS_REUSED", value="1"))
        for name, tool in (("semgrep", "semgrep"), ("gitleaks", "gitleaks"), ("history", "gitleaks"), ("trivy", "trivy")):
            if source_reused and name in TREE_STAGES:
                continue
            scan = "trivy-source" if gate_name and name == "trivy" else name
            stage = container(config, name, tool, ["sh", "/tools/security/security_scan.sh", scan], mode)
            # Reports are normalized by the summary, not retained raw by scanner stages.
            stage["volumeMounts"] = [m for m in stage["volumeMounts"] if m["name"] != "reports"]
            stages.append(stage)
    if gate_name:
        waiting = container(config, "await-image", "semgrep", ["python3", "/tools/security/security_run.py", "await-image"], mode)
        waiting["volumeMounts"].append(dict(name="gate-image", mountPath="/gate-image", readOnly=True))
        if await_timeout is not None:
            waiting["env"].append(dict(name="AWAIT_IMAGE_TIMEOUT_S", value=str(await_timeout)))
        stages.append(waiting)
        image_scan = container(config, "trivy-image", "trivy", ["sh", "/tools/security/security_scan.sh", "trivy-image"], mode)
        image_scan["volumeMounts"] = [m for m in image_scan["volumeMounts"] if m["name"] != "reports"]
        stages.append(image_scan)
    pod = dict(serviceAccountName="security-scanner", automountServiceAccountToken=False,
               nodeSelector={"kubernetes.io/hostname": config["node_name"]}, restartPolicy="Never",
               securityContext={"runAsNonRoot": True, "runAsUser": 1000, "runAsGroup": 1000, "fsGroup": 1000,
                                "seccompProfile": {"type": "RuntimeDefault"}},
               # No DNS egress exception: only these two service names are resolvable.
               dnsPolicy="None", dnsConfig={"nameservers": ["127.0.0.1"]},
               hostAliases=[dict(ip=config["registry_ip"], hostnames=[REGISTRY]),
                            dict(ip=config["service_ip"], hostnames=[SERVICE])],
               initContainers=stages,
               containers=[container(config, "summary", "semgrep", ["python3", "/tools/security/security_run.py", "finish"], mode)],
               volumes=[dict(name="scripts", configMap={"name": "security-scripts"}),
                        dict(name="source", persistentVolumeClaim={"claimName": "security-source", "readOnly": True}),
                        dict(name="work", emptyDir={"medium": "Memory", "sizeLimit": "4Gi"}),
                        dict(name="tmp", emptyDir={"medium": "Memory", "sizeLimit": "1Gi"}),
                        # Scanner databases and image layers (no findings): disk-backed, deleted with the pod.
                        # Measured 2026-09-30: in the 4 GiB memory-backed /work they exhausted the pod's memory.
                        dict(name="cache", emptyDir={"sizeLimit": "20Gi"}),
                        dict(name="reports", persistentVolumeClaim={"claimName": "security-reports"})])
    if config.get("trivy_cache_claim"):
        # Vulnerability DBs and analysed-layer cache (no findings, no source): survives between Jobs.
        pod["volumes"].append(dict(name="trivy-cache", persistentVolumeClaim={"claimName": config["trivy_cache_claim"]}))
    if mode == "gate":
        # Every gate Job -- split or not, the deploy's fallback full rescan included -- runs the scripts of the source
        # snapshot it judges (or, pre-merge, the staged deployed copy), never the installed ConfigMap: run_build.sh does
        # not refresh ConfigMap/security-scripts, so it may lag the release by any number of changes.
        pod["volumes"][0] = dict(name="scripts", persistentVolumeClaim={"claimName": "security-source", "readOnly": True})
        for c in pod["initContainers"] + pod["containers"]:
            for mount in c["volumeMounts"]:
                if mount["name"] == "scripts":
                    mount["subPath"] = pregate_tools or (commit + "/tools/security")
                elif pregate_tools and mount["name"] == "source" and mount.get("subPath") == "rules":
                    # The rules staged (and hashed into the verdict's policy) with the scripts, never the shared copy
                    # a concurrent deploy snapshot may replace while this Job runs.
                    mount["subPath"] = pregate_tools + "/rules"
    if gate_name:
        pod["volumes"].append(dict(name="gate-image", configMap={"name": gate_name}))
    if mode == "dast":
        pod["volumes"].append(dict(name="machine", secret={"secretName": "security-machine-token", "defaultMode": 288}))
    return dict(apiVersion="batch/v1", kind="Job", metadata={"name": "security-" + mode, "namespace": NS},
                spec={"backoffLimit": 0, "activeDeadlineSeconds": deadline, "ttlSecondsAfterFinished": 86400,
                      "template": {"metadata": {"labels": LABEL}, "spec": pod}})


def manifests(config):
    settings(config)
    items = [dict(apiVersion="v1", kind="Namespace", metadata={"name": NS,
              "labels": {"pod-security.kubernetes.io/enforce": "restricted"}}),
             dict(apiVersion="v1", kind="ServiceAccount", metadata={"name": "security-scanner", "namespace": NS},
                  automountServiceAccountToken=False)]
    items += [dict(apiVersion="v1", kind="PersistentVolumeClaim", metadata={"name": "security-source", "namespace": NS},
                   spec={"accessModes": ["ReadWriteOnce"], "resources": {"requests": {"storage": "20Gi"}}}),
              dict(apiVersion="v1", kind="PersistentVolume", metadata={"name": "security-reports"},
                   spec={"capacity": {"storage": "30Gi"}, "accessModes": ["ReadWriteOnce"], "storageClassName": "",
                         "persistentVolumeReclaimPolicy": "Retain",
                         "hostPath": {"path": "/var/lib/nexus-security", "type": "Directory"},
                         "claimRef": {"name": "security-reports", "namespace": NS},
                         "nodeAffinity": {"required": {"nodeSelectorTerms": [{"matchExpressions": [
                             {"key": "kubernetes.io/hostname", "operator": "In", "values": [config["node_name"]]}]}]}}}),
              dict(apiVersion="v1", kind="PersistentVolumeClaim", metadata={"name": "security-reports", "namespace": NS},
                   spec={"accessModes": ["ReadWriteOnce"], "storageClassName": "", "volumeName": "security-reports",
                         "resources": {"requests": {"storage": "30Gi"}}})]
    namespace = lambda name: {"matchLabels": {"kubernetes.io/metadata.name": name}}
    items.append(dict(apiVersion="networking.k8s.io/v1", kind="NetworkPolicy",
                      metadata={"name": "security-egress", "namespace": NS},
                      spec={"podSelector": {}, "policyTypes": ["Ingress", "Egress"], "ingress": [], "egress": [
                          {"to": [{"ipBlock": {"cidr": config["proxy_ip"] + "/32"}}],
                           "ports": [{"protocol": "TCP", "port": config["proxy_port"]}]},
                          {"to": [{"namespaceSelector": namespace("kube-system"), "podSelector": {
                              "matchLabels": {"app.kubernetes.io/component": "registry"}}}],
                           "ports": [{"protocol": "TCP", "port": 5000}]},
                          {"to": [{"namespaceSelector": namespace("ui2"), "podSelector": {
                              "matchLabels": {"app.kubernetes.io/component": "service"}}}],
                           "ports": [{"protocol": "TCP", "port": 8080}, {"protocol": "TCP", "port": 8086}]}]}))
    items.append(dict(apiVersion="v1", kind="ConfigMap", metadata={"name": "security-scripts", "namespace": NS},
                      data={name: (ROOT / "tools" / "security" / name).read_text() for name in
                            ("security_scan.sh", "security_summary.py", "security_run.py", "security_dast.py")}))
    for mode, schedule in (("daily", "30 2 * * *"), ("dast", "30 3 * * 0")):
        scan = job(config, mode)
        items.append(dict(apiVersion="batch/v1", kind="CronJob", metadata=scan["metadata"],
                          spec={"schedule": schedule, "timeZone": "Europe/Istanbul", "concurrencyPolicy": "Forbid",
                                "startingDeadlineSeconds": 900, "successfulJobsHistoryLimit": 2, "failedJobsHistoryLimit": 3,
                                "jobTemplate": {"spec": scan["spec"]}}))
    return dict(apiVersion="v1", kind="List", items=items)


def loader(config):
    c = container(config, "loader", "semgrep", ["sleep", "3600"], "daily")
    c["volumeMounts"] = [dict(name="source", mountPath="/source")]
    return dict(apiVersion="v1", kind="Pod", metadata={"name": "security-source-loader", "namespace": NS},
                spec={"serviceAccountName": "security-scanner", "automountServiceAccountToken": False,
                      "nodeSelector": {"kubernetes.io/hostname": config["node_name"]},
                      "restartPolicy": "Never", "securityContext": {"runAsNonRoot": True, "runAsUser": 1000,
                      "runAsGroup": 1000, "fsGroup": 1000, "seccompProfile": {"type": "RuntimeDefault"}},
                      "containers": [c], "volumes": [dict(name="source", persistentVolumeClaim={"claimName": "security-source"})]})


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--config", type=Path, required=True)
    p.add_argument("--mode", choices=("install", "loader", "gate"), default="install")
    p.add_argument("--image")
    p.add_argument("--commit")
    args = p.parse_args()
    try:
        config = settings(json.loads(args.config.read_text()))
        result = manifests(config) if args.mode == "install" else loader(config) if args.mode == "loader" else job(config, "gate", args.image, args.commit)
        print(json.dumps(result, indent=2))
    except (OSError, ValueError, TypeError, KeyError):
        raise SystemExit("Security manifest configuration invalid; reviewed digests and private host settings are required.")
