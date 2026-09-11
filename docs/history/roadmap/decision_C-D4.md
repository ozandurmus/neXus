# C-D4 — Maximum targets per operational-write request during the pilot.

## Options

- 1 target per request
- N targets per request
- Fleet selection

## Recommendation

1. The pilot proves the path, not throughput; a per-request ceiling of one makes an accidental multi-device write structurally impossible. Revisit only with real pilot evidence.
