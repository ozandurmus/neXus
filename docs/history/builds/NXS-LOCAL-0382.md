# NXS-LOCAL-0382 — Platform upkeep: UBI9 base refreshes, CVE baseline, ship pipeline and CI, HOST-A k3s and kernel

## Summary

The UBI9 bases were refreshed twice (Java 21.0.13, curl, xz; log4j-api 2.25.5), eMudhra roots are blocklisted, accepted findings get a daily review, and shipping gained a docs fast path, checks-gated merge and a bounded CI browser install (PRs 671-783). HOST-A runs k3s v1.36.6 on kernel 7.0.0-43 with its own AppArmor profile, the rwsafe pilot removed and the image registry pruned to about 17 GB; live e2e 80/80 after the reboot.

Evidence (2026-11-10 plan refresh): host ledger entries 5 and 6. Entry 5 (2026-10-31): kernel 7.0.0-42 stacks the container AppArmor label, so HOST-A carries its own cri-containerd profile. Entry 6 (2026-11-10): encrypted platform backup first, k3s v1.36.6 in place, 50 packages, kernel 7.0.0-43 (label still stacked, profile kept), rwsafe pilot exported encrypted then removed, e2e 79/80 then 80/80. Registry pruned from about 129 GB to about 17 GB, keeping running images, the images of the 20 release snapshots and the newest per repository. Security baseline: CVE-2026-91415 acceptances removed (PR 783); glibc findings wait for a Red Hat fixed package (backlog ubi_base_glibc_fix_pending). The corporate vulnerability scanner keeps a permanent host-firewall allow (PO 2026-11-10).
