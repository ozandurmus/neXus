# PO decision record — 2026-10-30: refresh the UBI9 runtime base, extend what is left by 14 days, delete the archived lockfile

**Status: RATIFIED — PO DECISION (chat, 2026-10-30, relayed by the coordinating session).**
Recorded by the engineering session on `lane/base-image-refresh-1030`. Backed by automated validation and by a build
and image scan on HOST-A. Nothing here has been deployed yet.

## 1. Context

`security/baseline.yaml` held 34 Trivy acceptances with review date 2026-10-31. Most were for the Red Hat UBI9
`openjdk-21-runtime` base of the service image and of the Packet Analysis image built from it (PO decisions 2026-10-09,
2026-10-15, 2026-10-23 and 2026-10-24). One was for the archived `history/tools/render-harness/bun.lock`
(happy-dom, CVE-2026-115895). On 2026-10-31 these acceptances expire. Last night's daily scan also reported a new HIGH,
unaccepted happy-dom finding in the same lockfile (CVE-2026-118660). That finding made the daily scan red.

## 2. The PO's answers (verbatim)

1. Base-image findings: **"Yenile + kalanlara 14 gün"** ("Refresh, and give the remaining ones 14 days").
2. Archived lockfile: **"Dosyayı sil"** ("Delete the file").

## 3. Decisions

| # | Decision | Status |
| --- | --- | --- |
| 1 | Pin the runtime stage of `ui2/Containerfile` to the newest published `ubi9/openjdk-21-runtime` digest. The Packet Analysis image inherits it through `FROM ${SERVICE_IMAGE}`. The emSign blocklist step stays in both Containerfiles. | RATIFIED. Done (§4). |
| 2 | Remove every acceptance that the new images no longer report. | RATIFIED. 20 removed (§5.1). |
| 3 | Extend every remaining base-image finding that still has no fixed package to 2026-11-14. Never extend a finding that has a fixed package. | RATIFIED. 17 extended (§5.2). 1 not extended because a fix exists (§5.3). |
| 4 | Delete `history/tools/render-harness/bun.lock` only. The rest of the archived harness stays. | RATIFIED. Done (§6). |

## 4. Base image

| | Digest | Tag | Built |
| --- | --- | --- | --- |
| Before | `sha256:856ee6542b8fdf09a065fd5439309fc3f3c821fb8efac48aa6191c93635dd907` | `1.24-3.1791779558` | 2026-10-12 |
| After | `sha256:b924b5d15de9585902c58eea76d0c1d6120921844cbc9e79683103e01b0200fd` | `1.24-3.1792711374` | 2026-10-22 |

How the digest was found: the tag list of `registry.access.redhat.com/ubi9/openjdk-21-runtime` was read on 2026-10-30.
`1.24-3.1792711374` is the newest version tag, and `latest` resolves to the same manifest-index digest. The Kaniko
build pulled it by digest through the build proxy, so the build's own registry path serves it. The build-stage bases
(`ubi9/openjdk-21`, `ubi9/nodejs-22-minimal`) are not changed here. They never reach a shipped image.

Evidence (HOST-A, 2026-10-30):

- Both images were built in `ui2-build` the way the preview builds them (`tools/e2e/hosta_preview_e2e.py`
  `build_manifest`: the host's Kaniko/proxy/CA template, a private emptyDir context). The Packet Analysis build used the
  same arguments as `tools/delivery/hosta_packet_analysis_image.py`. Both builds completed. This includes the emSign
  blocklist assertions and the tshark 4.6.9 version and plugin checks. The build Jobs were deleted afterwards.
- Both new images and the two images deployed today were scanned in `ui2-security` with the gate's own image scan:
  `security_scan.sh trivy-image`, the reviewed Trivy image from the host's private security configuration, the gate's
  environment, and findings normalized by `security_summary.project`. No raw scanner output left the pod. The scan Jobs
  were deleted afterwards.
- HIGH/CRITICAL findings: service image 19 → 11, Packet Analysis image 20 → 12. The new images have no HIGH or
  CRITICAL finding that the baseline does not already accept.

## 5. Baseline changes

### 5.1 Removed: no longer reported on the new images (20)

| CVE | Location (package) | Was due |
| --- | --- | --- |
| CVE-2026-84782 | `ui2/Containerfile runtime image: openssl-libs` | 2026-12-31 |
| CVE-2026-54876 | `ui2/Containerfile runtime image: openssl-libs` | 2026-12-31 |
| CVE-2026-86145 | `ui2/Containerfile runtime image: pcre2/pcre2-syntax` | 2026-12-31 |
| CVE-2026-89161 | `ui2/Containerfile runtime image: pcre2/pcre2-syntax` | 2026-12-31 |
| CVE-2026-66046 | expat | 2026-10-31 |
| CVE-2026-93990 | expat | 2026-10-31 |
| CVE-2026-77214 | expat | 2026-10-31 |
| CVE-2026-54876 | openssl-libs | 2026-10-31 |
| CVE-2026-84782 | openssl-libs | 2026-10-31 |
| CVE-2026-103111 | pcre2, pcre2-syntax | 2026-10-31 |
| CVE-2026-86145 | pcre2, pcre2-syntax | 2026-10-31 |
| CVE-2026-89161 | pcre2, pcre2-syntax | 2026-10-31 |
| CVE-2026-69184 | c-ares | 2026-10-31 |
| CVE-2026-114406 | ca-certificates (image location and both Containerfile locations) | 2026-10-31 |
| CVE-2026-115895 | `history/tools/render-harness/bun.lock: happy-dom` | 2026-10-31 |

CVE-2026-103111, CVE-2026-86145 and CVE-2026-89161 each had two rows (pcre2 and pcre2-syntax), and CVE-2026-114406
had three. Unqualified locations are `registry.kube-system.svc.cluster.local/nexus-ui2-service: <package>`.

### 5.2 Extended to 2026-11-14: still reported, no fixed package (17)

Each reason now ends with: "Extended 14 days by the PO decision 2026-10-30 ('Yenile + kalanlara 14 gün'); still no
fixed package."

| CVE | Package | Rows | Red Hat state (RHEL 9) |
| --- | --- | --- | --- |
| CVE-2026-88647, CVE-2026-88648 | gnutls (MEDIUM) | 2 | Affected |
| CVE-2026-76641 | expat | 1 | Affected |
| CVE-2026-75804 | openssl-libs (MEDIUM) | 1 | Affected |
| CVE-2026-53613 | libblkid, libmount, libsmartcols, libuuid | 4 | Affected |
| CVE-2026-53615 | libblkid, libmount, libsmartcols, libuuid, and `ui2/Containerfile runtime image: util-linux libs` | 5 | Affected |
| CVE-2026-91415 | java-21-openjdk-headless (image location and both Containerfile locations) | 3 | Affected |
| CVE-2026-91403 | java-21-openjdk-headless | 1 | **Will not fix** |

CVE-2026-91403: Red Hat's security data lists `java-21-openjdk` on RHEL 9 as "Will not fix". A base rebuild may
therefore never close this finding. It needs its own PO decision before 2026-11-14.

Unchanged: CVE-2026-53613 `ui2/Containerfile runtime image: util-linux libs` (still reported, review 2026-12-31).

### 5.3 Not extended: a fix exists, but the image lags (1)

**CVE-2026-111497, libbrotli (HIGH)** stays at 2026-10-31. Trivy reports the fixed version `1.0.9-10.el9_8.1`
(RHSA-2026:90495, 2026-10-26), and Rocky Linux 9 BaseOS carries `libbrotli-1.0.9-10.el9_8.1`. The finding is **not
from the UBI base**: the service image does not report it. It comes from the `libbrotli-1.0.9-9.el9_7` RPM that
`ui2/Containerfile.packet-analysis` pins from the frozen Rocky 9.7 vault. The old acceptance reason attributes it to the
base, and that is wrong. From 2026-11-01 the gate blocks every ship that carries the Packet Analysis image. This lasts
until that pin moves to the fixed package, or until the PO decides otherwise. The pin is not changed here, because
moving it off the frozen 9.7 vault changes the reproducibility design in `PACKET_ANALYSIS_ENGINE_IMAGE.md`.

Gate outcome for the new images with this baseline, worked out with `security_summary`: they pass up to 2026-10-31.
From 2026-11-01 to 2026-11-14 they block on CVE-2026-111497 only. From 2026-11-15 the 11 extended HIGH rows also block
unless they are renewed.

## 6. Archived lockfile

`history/tools/render-harness/bun.lock` is deleted. `package.json`, `check-render.mjs` and `check_render_playwright.py`
stay. No build or test reads the lockfile: `pytest.ini` excludes `history/`, and no Containerfile, Gradle build, CI
workflow or script refers to it. With the lockfile deleted, the Trivy source scan no longer reports CVE-2026-115895
(accepted until now), CVE-2026-118660 (HIGH, unaccepted, red in last night's daily scan) or CVE-2026-118661 (MEDIUM).

## 7. After this ships

The running pods keep the old base until the rollout. Between the merge and the rollout, the daily scan of the deployed
images reports the removed pcre2, expat and ca-certificates findings as unaccepted again. Ship and roll out promptly.
