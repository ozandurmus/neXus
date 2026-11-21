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

## 8. Refresh 2026-11-09 (PO: "Devam et. yükleyelim")
- **Runtime base.** `ubi9/openjdk-21-runtime` `sha256:cfa544effa28c5c57197a4c9d460812e70efc0965e6f518e1c2e787ff3cb9335`
  (`1.24-3.1793323798`, built 2026-10-30). Red Hat catalog RPM manifest of that image (read 2026-11-09):
  - `java-21-openjdk-headless-21.0.13.0.9-1.2.el9` (RHSA-2026:88349, fixes CVE-2026-91415);
  - `curl-minimal-7.76.1-40.el9_8.9`;
  - `xz-libs-5.2.5-8.el9_8.1`.
  
  glibc is unchanged (`2.34-283.el9_8`); its findings stay until Red Hat ships a fixed package.
- **Build-stage bases**, refreshed to the newest digests:
  - `ubi9/openjdk-21` `sha256:c8fcb7571677a9f6f1f2f3325c9765103ecc2834ff13ce20eb5ee6d1088f7e12` (2026-11-02), also
    in the integration job;
  - `ubi9/nodejs-22-minimal` `sha256:7c28182428503c3e98be630ac8d8c6582c39bb7d4bce34bb862de0cee0f00b25` (2026-11-09).
- **log4j-api.** POI 5.5.1 brought `log4j-api` 2.24.3 into packet-analysis, sanitizer and worker (CVE-2026-49844, trivy
  MEDIUM). A constraint now aligns them with the 2.25.5 that the rest of the build uses.
- **CVE-2026-91403.** Red Hat marks it "Will not fix" for `java-21-openjdk`. It is the eMudhra CA distrust; RHEL's
  OpenJDK reads the system trust store, so Red Hat's fix lives in `ca-certificates` (CVE-2026-114406). The emSign
  blocklist step (PO 2026-10-23) already removes those roots in both images and fails the build if one is still
  trusted. The acceptance stays, and its "remove when fixed" condition is now met by the blocklist, not by a Java
  package.
- **Acceptances.** Those the new image no longer reports are removed after the gate's own scan of the shipped image.
  Decision 2 is applied in a follow-up, from that scan's counts.

## Refresh of 2026-11-14 (31 acceptances due that day)

- **Runtime base.** `ubi9/openjdk-21-runtime` moves to `sha256:9a074c2ff90be60b12521ffd5e531acafd25fe36f1c177180cbe1176dfb36c84`
  (tag 1.24-3.1794341129, built 2026-11-10, RHEL 9.9 packages; the registry's `latest` on 2026-11-14). Against the
  previous pin it changes 34 of 131 packages (glibc 284.el9_8.4, systemd-libs, openldap, libxml2 16.el9, util-linux
  28.el9_9 and others). Build-stage bases are unchanged.
- **Evidence.** Package lists from the Red Hat container catalog for both digests; fix states from the Red Hat
  security data API; one trivy scan of both bases on HOST-A (the gate's own trivy image). The new base reports no
  HIGH or CRITICAL finding that the baseline does not already cover; the python3 findings it shows are removed by the
  image build.
- **Dropped (4).** CVE-2026-53613 on libblkid, libmount, libsmartcols and libuuid: fixed in util-linux
  2.37.4-28.el9_9 (RHSA-2026:97522), which the new base carries; trivy no longer reports it.
- **Re-time-boxed to 2026-11-28 (27)** under this record's 14-day rule, each with its installed version, Red Hat
  status and exposure in `security/baseline.yaml`: fixed by Red Hat but in no published base yet (krb5 x10,
  RHSA-2026:99715; util-linux CVE-2026-53615 x5, RHSA-2026:98519; openssl CVE-2026-75804, RHSA-2026:98484); no
  Red Hat fix (sqlite x4, json-glib, expat x2, gnutls x2, gnupg2); CVE-2026-91403 (CRITICAL, Will not fix for
  java-21-openjdk; covered by the emSign blocklist step).

## Amendment of 2026-11-18 (PO: "Evet, ekle")

Amended by `docs/design/PO_DECISION_RECORD_2026_11_18_RUNTIME_PACKAGE_UPDATE.md` (RATIFIED): the digest-pinned
`ubi9/openjdk-21-runtime` base stays the floor, and a resilient `microdnf -y update --setopt=install_weak_deps=0`
step runs in the runtime stage before the offline `python3*` removal step (recording updated NEVRAs to
`/app/build-info/package-update.txt` and logging `PACKAGE_UPDATE_SKIPPED: <reason>` if the Red Hat repositories are
unreachable through the build proxy).
