# UBI9 runtime base: glibc MEDIUM findings wait for a Red Hat fixed package; refresh the base when the RHSA lands (CVE-2026-91403 acceptance stays: eMudhra roots are blocklisted in the image)

status: planned · target: BASE_IMAGE_REFRESH record s8

2026-11-10 (plan refresh): opened at the PO's request. The 2026-11-09 runtime base (PO_DECISION_RECORD_2026_10_30_BASE_IMAGE_REFRESH.md section 8) still carries glibc 2.34-283.el9_8; its MEDIUM findings stay until Red Hat ships a fixed package. Refresh the base when the RHSA lands and remove the acceptances the new image no longer reports. CVE-2026-91403 is 'Will not fix' for java-21-openjdk; its acceptance stays because the emSign (eMudhra) roots are blocklisted in both images and the build fails if one is still trusted.
