# Refresh the pinned UBI9 base image digests when Red Hat ships fixed gnutls/expat/krb5-libs; then remove the time-boxed OS-library exceptions

status: planned · target: 2026-10-31: OS-library baseline exception review date

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Refresh the pinned UBI9 base image digests when Red Hat ships fixed gnutls/expat/krb5-libs; then remove the time-boxed OS-library exceptions

Target (full): 2026-10-31: OS-library baseline exception review date

2026-11-10 (plan refresh): done. The runtime and build bases were refreshed on 2026-10-30 (PR 683) and 2026-11-09 (PR 782: java-21-openjdk 21.0.13 fixing CVE-2026-91415, curl-minimal, xz-libs; log4j-api aligned to 2.25.5); acceptances the new images no longer report were removed (PR 783). glibc is unchanged in the newest base; that wait moves to ubi_base_glibc_fix_pending.

2026-11-18 (PO decision 'Evet, ekle', PO_DECISION_RECORD_2026_11_18_RUNTIME_PACKAGE_UPDATE.md amending PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md): added a resilient runtime-stage OS package update step (timeout 180 microdnf -y update --setopt=install_weak_deps=0 before the offline python3* RPM removal, with daily ARG PACKAGE_UPDATE_DATE cache-buster, recording updated NEVRAs to /app/build-info/package-update.txt and falling back with PACKAGE_UPDATE_SKIPPED if Red Hat repositories are unreachable through the build proxy). The pinned ubi9/openjdk-21-runtime digest stays the floor.
