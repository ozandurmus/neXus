# Packet Analysis engine image (UBI 9 + JRE 21 + tshark 4.6.9)

## Status

**DRAFT — prepared for the Product Owner's freeze, 2026-10-15.** Built, pushed and gate-scanned on every deploy once
merged; no workload uses it yet. `ui2-packet-analysis` (`deploy/ui2/58-packet-analysis-deployment.yaml`, written on
its own branch) switches to it later. Implements `PACKET_ANALYSIS_PORT_CONTRACT.md` §5 ("moves to its own image (UBI9
+ JRE 21 + tshark) so tshark never enters the other neXus images"), whose §7 keeps "image build (tshark packaging on
UBI9)" out of scope until frozen: this document is the proposal for that freeze. Decision record:
`PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md` §4 (RATIFIED).

**Contradiction to resolve (AGENTS.md, Authority hierarchy).** `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
(FROZEN) EP-1 says "One image, one entry point. The workload role is selected by an argument, not by a separate image,
a separate tag". The ratified decision record §4 ("installed in the analysis worker image") and the port contract §5
require a second image. Not reconciled here: the Product Owner needs to amend EP-1 (suggested wording: "one image per
dependency boundary; the Packet Analysis engine image is the main image plus tshark, same entry point and role
argument") before `ui2-packet-analysis` is switched. The same contract's §3.1 names the UBI 9 OpenJDK 21 family as the
bases; the build stage below is Rocky Linux 9.7 (build stage only — nothing from it reaches the runtime except
`/opt/wireshark` and two RPMs); that also wants a line in the amendment.

## 1. Choice: tshark built from the pinned 4.6.9 source (route c)

| Route | Result (checked 2026-10-15 against the repositories' own metadata) |
|---|---|
| (a) UBI 9 repositories | No `wireshark-cli` in ubi-9 baseos, appstream or codeready-builder. They also lack `c-ares`/`c-ares-devel` and `flex`, both required by the Wireshark build. Not possible. |
| (b) EL9 package (CentOS Stream 9 / Rocky 9 / EPEL) | EL9 ships `wireshark-cli-3.4.10` (Rocky 9.7: `3.4.10-8.el9_7.1`); EPEL does not carry Wireshark. 3.4 vs 4.6.9 is two major lines of dissector and field changes — an unacceptable parity risk for the frozen-corpus harness. Its `dumpcap` also carries file capabilities. Rejected. |
| (c) Source build | **Chosen.** Exactly **4.6.9**, the parity reference's version (`TShark (Wireshark) 4.6.9 (v4.6.9-0-g2d548b197c75)` on the operator's Mac). |

The build stage is Rocky Linux 9.7 (RHEL 9 binary compatible), its package set frozen by pointing `dnf` only at the
9.7 vault (9.8 is current, so 9.7 no longer changes). The runtime is the main neXus image itself: tshark links only
against libraries the pinned `ubi9/openjdk-21-runtime` base already carries (verified against the Red Hat catalog RPM
manifest of digest `sha256:45af2087…aa55d`, tag `1.24-3.1790283193`) plus two pinned RPMs.

## 2. What is built

`ui2/Containerfile.packet-analysis`, two stages:

1. `tshark-build` — `quay.io/rockylinux/rockylinux:9.7.20251123@sha256:53f4c6dcb34e1403bd93207351f0af9a593610faeb7165cb8a037346765199b0`.
   Vault-only repositories with `gpgcheck=1`, every top-level package by exact version; Wireshark
   `wireshark-4.6.9.tar.xz` verified against SHA-256
   `d062d416a1ab3a49975aafe3a671c882dde0562ccd71b078808ceb59d465fc0f` (from the signed `SIGNATURES-4.6.9.txt`);
   CMake/Ninja build of **tshark only**, installed stripped to `/opt/wireshark` (RPATH `/opt/wireshark/lib64`,
   headers/pkgconfig/man removed); `c-ares-1.19.1-2.el9_4` (SHA-256 `64b71af9…b532`) and `libbrotli-1.0.9-10.el9_8.1` (CVE-2026-111497 fix)
   (SHA-256 `ce72c3ec…2200`) fetched from the Rocky vault (c-ares) and the current 9.8 tree (libbrotli), checksum- and signature-checked.
2. Final stage — `FROM ${SERVICE_IMAGE}`: the main image just built from the same commit, **by digest** (the stage
   refuses anything but `registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:…`). Installs the two RPMs
   into the package database, copies `/opt/wireshark`, links `/usr/local/bin/tshark` (the engine's `TsharkRunner.find`
   looks up `tshark` on PATH), repeats the main image's write-bit sweep, checks `tshark --version` and that plugins
   load, ends `USER 185`.

Why a sibling file built FROM the main digest rather than a `--target` of `ui2/Containerfile`: Kaniko builds one
target per run and cannot hand one run's stage output to another, so a target would re-run the frontend and Gradle
build — the same source, but not the same bytes. Building FROM the main digest makes the JRE, `service.jar`, the
project files and the hardened filesystem the main image's own layers (provable: `sha256sum /app/service.jar` is equal
in both), and leaves `ui2/Containerfile` and the main build Job untouched (the stale-host-template risk of a changed
stage layout does not exist).

### Build options against the parity reference

| Feature | Mac reference 4.6.9 | This image | Parity note |
|---|---|---|---|
| GLib / Gcrypt / PCRE2 / libxml2 / zlib / zstd / LZ4 / GnuTLS / Kerberos (MIT) / nghttp2 / brotli / c-ares | yes | yes (EL9 versions) | same features; library versions differ (e.g. GLib 2.68 vs 2.88) |
| Plugins (epan dissectors, stats_tree, ipaddr, usbdump, g711/l16mono codecs) | yes | yes | same dissector plugins (`tshark -G plugins`) |
| libpcap, dumpcap, extcap tools | yes | **no** | file reading only; no capture privilege needed |
| zlib-ng | yes | no (zlib) | decompression output identical |
| Lua 5.4, libsmi, MaxMind, Snappy, xxhash, nghttp3, codecs (opus, sbc, g722/726/729, ilbc, amrnb) | yes | no | Lua: no scripts are loaded; libsmi: SNMP OID names only; MaxMind: needs a database, none; Snappy: Kafka-style decompression; nghttp3: HTTP/3 headers with keys only; codecs: RTP playback, not field export |

Residual risk: a field the engine exports that depends on one of the "no" rows, or a libc/GLib formatting difference.
The parity harness (L1 `packets` row hash on the frozen corpus) is the gate that proves or refutes it; the smoke test
below compares one decode with the Mac.

## 3. Pinning, privileges, writable set

- Every base by digest (Rocky by manifest-list digest; the final base by the main image's digest, passed as a build
  argument and checked in the stage); no `latest`; no package-manager run in the final stage; no download at container
  runtime.
- No setuid/setgid file and no file capability is added: `dumpcap` is not built, nothing calls `setcap`, both stages
  check `find /opt/wireshark -perm /6000` is empty and the two RPMs carry no `s` bit.
- Non-root, random-UID friendly: `USER 185`, everything owned `0:0`, `/opt/wireshark` group-0 readable, not writable;
  the closed writable set stays exactly `/app/tmp` and `/app/home` (FS-3); tshark's personal settings would go to
  `HOME=/app/home`. No Python interpreter (removed by the main image; the Rocky stage's own never leaves it).
- Entry point and role argument are the main image's (`java … -jar /app/service.jar`); the deployment passes
  `worker packet_analysis`.

## 4. Build, tag, scan

`deploy/ui2-image-build/run_build.sh`, after the main image's digest is read and before the security gate:

1. `tools/delivery/hosta_packet_analysis_image.py build --commit <sha> --service-image <main@digest>` creates Job
   `ui2-build/ui2-packet-analysis-image-build` from the host's own `~/build-job-proxy.yaml` (proxy, corporate CA,
   tolerations inherited; tag frozen), Kaniko args: `--dockerfile=/workspace/ui2/Containerfile.packet-analysis`,
   `--build-arg=SERVICE_IMAGE=<main@digest>`, `--destination=…/nexus-ui2-service:<commit12>-pa`,
   `--insecure-registry`/`--skip-tls-verify-registry` for the in-cluster registry (base pull), `--cache=true` with the
   template's `nexus-ui2-build-cache` repository and `--cache-ttl=2160h` (the tshark stage is compiled once and reused;
   all its inputs are pinned), memory limit 8 GiB, deadline 90 min; the digest comes back through the termination
   message, exactly like the e2e runner. A fresh successful Job for the same commit and base is reused.
2. The gate: `security_host.py gate --image <main@digest> --image <pa@digest>`; the split-gate request carries
   `images`, `security_run.py await-image` adds a `trivy image` scan and a CycloneDX SBOM for each, one verdict for
   both. Same repository name, so findings and baseline acceptances in the shared layers are the same rows.
3. Rollout: `module_deploy.py --targets ... --image <main@digest> --packet-analysis-image <pa@digest>`. The
   `packet-analysis` target gets only the engine image and fails closed (Blocked) when the argument is missing or equals
   the main image; `run_build.sh` drops the target (with a note) when `NEXUS_BUILD_PACKET_ANALYSIS_IMAGE=0` (also passed
   by `hosta_deploy.sh`) skips step 1. Every release renders `deploy/ui2/58-packet-analysis-deployment.yaml` with the
   engine image, checks it with a server-side diff before the drain and applies it after the drain, so env and resource
   changes ship with the code. A rollback restores the snapshot's recorded image only. A failed analysis-image build
   stops the deploy before the gate and any rollout.

The daily scan picks the image up by itself once a Deployment uses it.

Timing: the first build compiles Wireshark (`UNKNOWN`; estimate 20–40 min at 3 CPUs) — longer than the split gate's
`await-image` budget (3000 s), so **warm the cache once (§5) before the first deploy after merge**. Later builds reuse
the cached tshark layers: pull of the main image plus two small layers.

Egress `UNKNOWN` until the first build (as BP-6): the builder must reach `quay.io`, `dl.rockylinux.org` and
`www.wireshark.org` through the proxy.

## 5. Host commands (orchestrator; build + smoke test)

```bash
# On HOST-A, ~/nexus at the commit to test, no deploy running (check in-flight jobs first).
cd ~/nexus && COMMIT=$(git rev-parse HEAD)
# 1. Context + config map exactly as run_build.sh streams them.
kubectl apply -f deploy/ui2-image-build/00-namespace.yaml -f deploy/ui2-image-build/10-context-pvc.yaml
kubectl delete pod ui2-build-context-loader -n ui2-build --ignore-not-found --force --grace-period=0
kubectl apply -f deploy/ui2-image-build/20-context-loader.yaml
kubectl wait --for=condition=Ready pod/ui2-build-context-loader -n ui2-build --timeout=60s
tar -cf - ui2 project docs | kubectl exec -i -n ui2-build ui2-build-context-loader -- \
  sh -c "rm -rf /workspace/ui2 /workspace/project /workspace/docs && tar -xf - -C /workspace"
kubectl -n ui2-build create configmap ui2-build-config --from-literal=image_tag="${COMMIT:0:12}" \
  --from-literal=commit="$COMMIT" --dry-run=client -o yaml | kubectl apply -f -
# 2. Build FROM the running main image (by digest); prints the pushed reference. Follow the log in a second shell:
#    kubectl -n ui2-build logs -f job/ui2-packet-analysis-image-build
SERVICE_IMAGE=$(kubectl -n ui2 get deploy ui2-service -o jsonpath='{.spec.template.spec.containers[0].image}')
PA_IMAGE=$(python3 tools/delivery/hosta_packet_analysis_image.py build --commit "$COMMIT" --service-image "$SERVICE_IMAGE")
echo "$PA_IMAGE"
# 3. Smoke test under a random UID, read-only root, no capabilities.
kubectl -n ui2-build run pa-smoke --rm -i --restart=Never --image="$PA_IMAGE" --overrides='{"apiVersion":"v1","spec":{
 "securityContext":{"runAsNonRoot":true,"runAsUser":1000680000,"runAsGroup":0,"seccompProfile":{"type":"RuntimeDefault"}},
 "containers":[{"name":"pa-smoke","image":"'"$PA_IMAGE"'","command":["sh","-c",
  "tshark --version | head -1; java -version 2>&1 | head -1; sha256sum /app/service.jar; command -v tshark; tshark -G plugins | wc -l; echo 1MOyoQIABAAAAAAAAAAAAP//AAABAAAAAHjnaAAAAAAvAAAALwAAAAIAAAAAAgIAAAAAAQgARQAAIQABAABAEfbHwAACAcAAAgKcQAA1AA0AAG5lWHVz | base64 -d > /app/tmp/s.pcap; tshark -n -r /app/tmp/s.pcap -T fields -e frame.number -e ip.src -e ip.dst -e udp.dstport -e frame.protocols; echo suid:; find / -xdev -perm /6000 -type f 2>/dev/null | sort"],
  "securityContext":{"allowPrivilegeEscalation":false,"readOnlyRootFilesystem":true,"capabilities":{"drop":["ALL"]}},
  "resources":{"requests":{"cpu":"100m","memory":"256Mi"},"limits":{"cpu":"1","memory":"1Gi"}},
  "volumeMounts":[{"name":"tmp","mountPath":"/app/tmp"},{"name":"home","mountPath":"/app/home"}]}],
 "volumes":[{"name":"tmp","emptyDir":{}},{"name":"home","emptyDir":{}}]}}'
kubectl -n ui2 exec deploy/ui2-service -- sha256sum /app/service.jar   # must equal the line above
# 4. Image sizes (compressed layers) of the main and the analysis image.
for ref in "$SERVICE_IMAGE" "$PA_IMAGE"; do
  kubectl -n ui2-build exec ui2-build-context-loader -- curl -s \
    -H 'Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
    "http://registry.kube-system.svc.cluster.local/v2/nexus-ui2-service/manifests/${ref#*@}" |
    python3 -c 'import json,sys; m=json.load(sys.stdin); print(sys.argv[1][-12:], round(sum(l["size"] for l in m["layers"])/2**20), "MiB")' "$ref"
done
kubectl delete pod ui2-build-context-loader -n ui2-build --ignore-not-found
```

Expected: `TShark (Wireshark) 4.6.9 (…)`; `openjdk version "21…`; the same jar SHA-256 as `ui2-service`;
`/usr/local/bin/tshark`; a plugin count above zero; the decode line
`1	192.0.2.1	192.0.2.2	53	eth:ethertype:ip:udp:dns` (identical to the Mac reference's output for the same
bytes); the setuid list equal to the main image's (none added). Record the first-build duration and both sizes here.

## 6. Security scan coverage and licence

- Trivy sees the main image's RPMs plus `c-ares` and `libbrotli` (Rocky builds of the RHEL packages, matched against
  Red Hat advisories by version — confirm on the first gate run). It does **not** see the source-built Wireshark
  (`/opt/wireshark` has no package record): Wireshark security advisories (`wnpa-sec-*`) are followed by hand, and a
  bump is the three pins in `Containerfile.packet-analysis` (version, SHA-256, and this document).
- tshark/Wireshark is **GPL-2.0-or-later**. Here it is distributed only inside an internal image, unmodified, built
  from the published source whose URL and SHA-256 are in the repository (the corresponding source is therefore
  identifiable for any recipient). Whether internal distribution needs anything more (e.g. a notice in the image) is a
  **Product Owner / legal decision, pending**; nothing in this change depends on it.
