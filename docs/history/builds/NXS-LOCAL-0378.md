# NXS-LOCAL-0378 — Path analysis phase A: topology, path engine, Path screen, validation on devices (A0-A6)

## Summary

Route and interface facts (V184), per-hop policy and NAT evaluation (V185), an in-memory topology with conflicts and overrides (V186), the path engine, the Path screen and validation against the vendors' own read-only matchers (V187-V188) shipped under the frozen path design (PRs 715-739). Live: 20 of 20 masked path queries computed and the first device validation returned MATCH; UDP and chained NAT are phase B.
