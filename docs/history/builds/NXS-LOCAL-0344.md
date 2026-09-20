# NXS-LOCAL-0344 — Failover engine phase B: cryptographic 4-eyes dual control and read-only dry run

## Summary

Implemented 4-eyes dual control (requesterId != approverId), single-use HMAC-SHA256 lease tokens with length-prefixed canonical framing, and read-only dry-run plan compilation. No device write is enabled.

Status corrected to blocked on 2026-09-20 under NXS-LOCAL-0347. The two external final reviews adjudicate the Failover Engine as one unit across Phases A-D and both returned REJECTED, so Phase B cannot stand as delivered while the engine it belongs to is rejected. The verified open defects and the containment observation are recorded against NXS-LOCAL-0345; this record points there rather than restating them.
