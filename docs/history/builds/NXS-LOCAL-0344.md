# NXS-LOCAL-0344 — Failover engine phase B: cryptographic 4-eyes dual control and read-only dry run

## Summary

Implemented 4-eyes dual control (requesterId != approverId), single-use HMAC-SHA256 lease tokens with length-prefixed canonical framing, and read-only dry-run plan compilation. No device write is enabled.
