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
