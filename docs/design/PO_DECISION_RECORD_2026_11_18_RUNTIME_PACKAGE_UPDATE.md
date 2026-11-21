# PO decision record — 2026-11-18: resilient OS package update step in the UBI9 runtime image build

**Status: RATIFIED — PO DECISION (chat, 2026-11-18, "Evet, ekle").** Amends `PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md`.

## 1. Context

Under `PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md`, the runtime stage of `ui2/Containerfile` is pinned by
manifest-index digest (`FROM registry.access.redhat.com/ubi9/openjdk-21-runtime@sha256:...`) and previously ran no
package-manager update in the runtime stage, removing only the `python3*` RPMs offline via `rpm -e --nodeps`.

Red Hat publishes security-fixed RPMs to the UBI 9 yum repositories before publishing a rebuilt
`ubi9/openjdk-21-runtime` container image. On 2026-11-18, 20 HIGH, 34 MEDIUM and 3 LOW CVEs across installed runtime
packages (`curl-minimal`, `libcurl-minimal`, `expat`, `krb5-libs`, `openssl-libs`, `glibc`, `libxml2`, `util-linux` and
related subpackages) already have fixed packages in the UBI 9 repositories while waiting for a new base-image digest.
`tools/security/security_summary.py` (`accepted_review`) already surfaces baseline acceptances whose underlying package
now has an upstream fix (`fix_available`).

## 2. Product Owner risk question and verbatim answer

The four trade-offs and mitigations below were put to the Product Owner before adding a runtime package-update step:

| Risk / trade-off | Assessment & mitigation |
| --- | --- |
| **Build time** | Adds ~1–2 minutes to an uncached runtime stage build while fetching UBI 9 repository metadata and updated RPMs. Accepted. |
| **Proxy dependency** | The in-cluster Kaniko build reaches Red Hat's UBI 9 CDN through the build job's corporate proxy environment (`HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` in `deploy/ui2-image-build/30-build-job.yaml` and `31-build-job-proxy.yaml.template`) and optional corporate CA anchors (`ui2/.ca/*.pem`). |
| **Non-reproducibility** | Two builds of the same Git commit on different days may resolve newer UBI 9 RPM versions, so builds are no longer bit-for-bit reproducible from the Git tree alone. Mitigated by recording the exact updated package NEVRA list inside the image (`/app/build-info/package-update.txt`) and in the build log, and by deploying and scanning images by immutable `@sha256:` digest. |
| **Resilient fallback** | If the Red Hat repositories are unreachable through the proxy or `microdnf` times out, the build logs `PACKAGE_UPDATE_SKIPPED: <reason>` to the build log and `/app/build-info/package-update.txt` and continues with the pinned base image's packages rather than failing the build. |

Product Owner decision (chat, 2026-11-18, verbatim): **"Evet, ekle"** ("Yes, add it").

## 3. Amendment to `PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md`

1. **Pinned base digest stays the floor.** The runtime stage continues to start from a digest-pinned
   `registry.access.redhat.com/ubi9/openjdk-21-runtime@sha256:...` base (`PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md`
   Decision 1). Periodic base-image digest refreshes remain in effect so the offline fallback floor advances as Red Hat
   publishes new runtime images.
2. **Update all installed packages (`microdnf -y update --setopt=install_weak_deps=0`).**
   - **Choice:** Update all installed packages rather than a hand-maintained list of security-sensitive package names.
   - **Justification:**
     1. *Subpackage coupling:* UBI 9 core libraries share strict version-locked dependencies across subpackages
        (`glibc` / `glibc-common` / `glibc-minimal-langpack`, `curl-minimal` / `libcurl-minimal`,
        `util-linux` / `util-linux-core` / `libblkid` / `libmount` / `libsmartcols` / `libuuid`, `krb5-libs`,
        `openssl-libs`, `libxml2`, `expat`). Updating only named packages risks RPM solver conflicts or missed sibling
        subpackages when an advisory spans multiple RPMs.
     2. *Complete coverage without Containerfile churn:* Updating all installed packages closes every published Red Hat
        RPM fix across the minimal runtime set automatically on the next build, while `--setopt=install_weak_deps=0`
        prevents weak dependencies from expanding the installed package set.
     3. *Preserved post-update invariants:* Because the offline `python3*` removal step, the `emSign` CA blocklist step
        and the `FS-1`..`FS-3` write-bit hardening sweep all run *after* the update step, no updated or pulled-in
        package can leave scripting runtimes, distrusted CAs or writable paths in the final image.
3. **Resilient runtime update step and daily cache-buster.**
   - Corporate CA anchors (`ui2/.ca/*.pem`) are installed into `/etc/pki/ca-trust/source/anchors/` before the update
     step so `microdnf` trusts the corporate TLS proxy.
   - `ARG PACKAGE_UPDATE_DATE=2026-11-18` precedes the update `RUN` instruction and is referenced inside it so layer
     caching refreshes daily when passed a current date, even when no tracked file changes.
   - One `RUN` step in the runtime stage (before the `python3*` RPM removal step):
     - records pre-update NEVRAs (`rpm -qa --qf '%{NEVRA}\n' | sort`),
     - runs `timeout 180 microdnf -y update --setopt=install_weak_deps=0` using the build job's proxy environment,
     - on success, writes the sorted diff of updated package NEVRAs (`comm -13`) to `/app/build-info/package-update.txt`
       and prints it to the build log (`tee`),
     - on failure or timeout, logs `PACKAGE_UPDATE_SKIPPED: <reason>` to standard error and
       `/app/build-info/package-update.txt` and continues with the pinned base packages,
     - cleans all `microdnf` caches (`(microdnf clean all || true)` and
       `rm -rf /var/cache/yum /var/cache/dnf /tmp/rpm-before.txt /tmp/rpm-after.txt`) in the same `RUN`.
   - No other `dnf`, `microdnf` or `yum` invocation is permitted in the runtime stage.
4. **Offline `python3*` removal stays after the update.** `RUN rpm -e --nodeps $(rpm -qa --qf '%{NAME}\n' 'python3*') && test -z "$(rpm -qa 'python3*')"` runs immediately after the update step so any `python3*` package present in the base or pulled in during an update transaction is erased offline and verified absent in the same layer.
5. **ENTRYPOINT and JVM flags unchanged.** `ENTRYPOINT` keeps `-XX:-UseBASE64Intrinsics` (interim mitigation for
   CVE-2026-114938 / `java4shell_redhat_fixed_jdk`) and all existing flags; `USER 185` stays final.
6. **Derived images inherit the updated runtime layer.** `ui2/Containerfile.packet-analysis` and
   `ui2/Containerfile.restamp` build `FROM ${SERVICE_IMAGE}` (the digest of the built `ui2/Containerfile` image) and
   inherit the updated OS packages and `/app/build-info/package-update.txt` without duplicating the update step.

## 4. Verification on the next HOST-A build

Because container builds run only in-cluster on HOST-A (`ui2-build`), verify on the next build:

1. **Warning absent:** The Kaniko build log contains the updated package NEVRA lines and does **not** contain
   `PACKAGE_UPDATE_SKIPPED:`.
2. **Package list file present:** `/app/build-info/package-update.txt` exists in the built image, is owned by `0:0`
   with write bits stripped, and lists the updated package NEVRAs (without `PACKAGE_UPDATE_SKIPPED:`).
3. **Trivy `fix_available` count drops:** In the security gate's image scan (`security_scan.sh trivy-image` normalized
   by `tools/security/security_summary.py`), the `fix_available` count in `accepted_review` and the reported
   HIGH/MEDIUM/LOW package findings drop for the packages updated from UBI 9 (`curl-minimal`, `libcurl-minimal`,
   `expat`, `krb5-libs`, `openssl-libs`, `glibc`, `libxml2`, `util-linux`), allowing stale acceptances in
   `security/baseline.yaml` to be retired in a follow-up once the built digest is scanned.

## Restoration note (2026-11-21)

Batch `4ff5206b` (static route phase R0, 2026-11-19) was assembled on a base older than `4c1a034a` and removed this record,
the runtime-stage update step and its tests without saying so. Nothing in that batch withdrew this decision. The
2026-11-21 security gate then blocked a deploy on four fixed curl HIGHs (CVE-2026-8286, -8458, -8927, -9547) that this
step exists to pick up. `4c1a034a` is restored unchanged, on top of the 2026-11-19 base digest, with
`PACKAGE_UPDATE_DATE=2026-11-21`.

## Retry note (2026-11-21)

The build proxy returned intermittent HTTP 503 for the Red Hat UBI CDN during the 2026-11-21 deploys. One build hit a
503, logged PACKAGE_UPDATE_SKIPPED, and the image kept four curl CVEs whose fix (curl 7.76.1-44.el9_9.1) was already in
the UBI baseos repository, so the security gate blocked the deploy. The update step now tries `microdnf update` three
times, 30 s apart, before falling back; the fallback and the ratified decision are unchanged. The script-execution
image install step retries the same way and still fails the build if all three attempts fail.

## Root cause correction (2026-11-21)

The 503s were not intermittent proxy failures. Kaniko does not pass the build pod's HTTP_PROXY, HTTPS_PROXY and
NO_PROXY environment to RUN steps, so microdnf inside the image build never used the proxy: a probe RUN step saw no
proxy variables and could not reach the CDN, while the same image and env in a plain pod reached it every time. With
`--build-arg=HTTP_PROXY`, `--build-arg=HTTPS_PROXY` and `--build-arg=NO_PROXY` (valueless predefined build args taken
from the pod env) the same RUN step reached the CDN and microdnf built its metadata cache. The service build template
and the script-execution image builder now pass them; the rendered `~/build-job-proxy.yaml` on the build host carries
the same three lines. The update step has most likely never applied an update in the in-cluster build before this
fix.

## Script-execution image: nodejs module stream 22 to 24 (2026-11-21)

Evidence is public Red Hat data only: `access.redhat.com/hydra/rest/securitydata/cve/<CVE>.json` and the public UBI 9
appstream repodata. UBI 9 appstream carries nodejs streams 18, 20, 22 and 24 (no 26). Stream 22 is at
`22.23.4-2.module+el9.8.0+25286+62e50b69`; stream 24 is at module version `9080020261105135730` with
`nodejs-1:24.22.1-5.module+el9.8.0+25292+33251f3b` and the same NEVRA for `nodejs-libs`.

| CVE | Stream 22 | Stream 24 (fix) |
|---|---|---|
| CVE-2026-119373, -119374, -119379 | Affected | fixed, RHSA-2026:96226 (module build `9080020261105135730`, equal to the UBI stream 24 build) |
| CVE-2026-84961, CVE-2026-85152 | Will not fix | fixed, RHSA-2026:73838 (earlier build `9080020260921144317`, contained in the later 24.22.1-5 build) |
| CVE-2026-123864, -123865, -123866, -123872 | Affected | Affected (no fix on any UBI 9 stream) |

`Containerfile.script-exec` now enables `nodejs:24`. The 10 acceptances (5 CVEs x `nodejs`, `nodejs-libs`) for the first
two rows are removed from `security/baseline.yaml`; the 8 acceptances for the last row stay, review date 2026-11-28.
Not checked here: CVEs that affect stream 24 but not 22; the next scan of the built digest shows them.

Operator-script impact (Node 22 to 24, from Node release notes, not tested here): V8 moves to 13.x (new JS features, a
few changed edge cases); npm 11 replaces npm 10; `url.parse()` now emits a runtime deprecation warning on stderr;
more long-deprecated APIs are removed or throw (for example legacy `util.is*` helpers, `tls.createSecurePair`);
`AsyncLocalStorage` uses a new implementation. Scripts relying on deprecated APIs may print warnings or fail; the
Script Execution result shows stderr and the exit code as before.
