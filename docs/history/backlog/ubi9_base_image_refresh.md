# Refresh the pinned UBI9 base image digests when Red Hat ships fixed gnutls/expat/krb5-libs; then remove the time-boxed OS-library exceptions

status: planned · target: 2026-10-31: OS-library baseline exception review date

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Refresh the pinned UBI9 base image digests when Red Hat ships fixed gnutls/expat/krb5-libs; then remove the time-boxed OS-library exceptions

Target (full): 2026-10-31: OS-library baseline exception review date

2026-11-10 (plan refresh): done. The runtime and build bases were refreshed on 2026-10-30 (PR 683) and 2026-11-09 (PR 782: java-21-openjdk 21.0.13 fixing CVE-2026-91415, curl-minimal, xz-libs; log4j-api aligned to 2.25.5); acceptances the new images no longer report were removed (PR 783). glibc is unchanged in the newest base; that wait moves to ubi_base_glibc_fix_pending.
