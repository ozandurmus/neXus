# NXS-LOCAL-0399 — Failover one-action dialog, PAN config-sync member, preview golden database and Estate UNKNOWN fixes

## Summary

Failover screens give an administrator one action and a work log note (frontend only, authority unchanged, f880ee45), and PAN readiness names the member that has config sync off. Preview ships get parallel image builds, a golden database copy and separate preview/deploy locks (fb373545, 6497ab96), and Estate diagnostic runs, Configuration, content versions and failover roles never show an unreported value as OK or zero (528a728d, a455d037, 5e635cf6, 95b4268a, 7a220b14).

## Batches

fb373545, 95b4268a, 6497ab96, a455d037, 528a728d, a1e219c1, f880ee45, 5e635cf6, 7a220b14 (2026-11-23, 10:00 through 12:22; batches auto-1123-1034 through auto-1123-1222).

## Open

Preview golden database roll-out on the host is an operator step (`docs/operations/PREVIEW_GOLDEN_DATABASE.md`); the image assumptions stay UNKNOWN until its first run.
