# UBI9 runtime base: glibc MEDIUM findings wait for a Red Hat fixed package; refresh the base when the RHSA lands (CVE-2026-91403 acceptance stays: eMudhra roots are blocklisted in the image)

status: planned · target: BASE_IMAGE_REFRESH record s8

2026-11-10 (plan refresh): opened at the PO's request. The 2026-11-09 runtime base (PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md section 8) still carries glibc 2.34-283.el9_8; its MEDIUM findings stay until Red Hat ships a fixed package. Refresh the base when the RHSA lands and remove the acceptances the new image no longer reports. CVE-2026-91403 is 'Will not fix' for java-21-openjdk; its acceptance stays because the emSign (eMudhra) roots are blocklisted in both images and the build fails if one is still trusted.

2026-11-14 backlog truth: still open (P2, size S). Today: Scan acceptances stay until vendor ships a fix; periodic check. Evidence: BASE_IMAGE_REFRESH record s8: glibc 2.34-283.el9_8 MEDIUM findings wait for Red Hat package

2026-11-18 (PO decision 'Evet, ekle', PO_DECISION_RECORD_2026_11_18_RUNTIME_PACKAGE_UPDATE.md): ui2/Containerfile now runs a resilient microdnf -y update --setopt=install_weak_deps=0 step in the runtime stage before the offline python3* RPM removal, so published Red Hat UBI 9 RPM fixes (including glibc, curl-minimal, libcurl-minimal, expat, krb5-libs, openssl-libs, libxml2, util-linux) land on the next image build without waiting for a new base-image digest. Verify on the next HOST-A build and retire the resolved baseline acceptances once scanned.
