# Log detection P1 runs in shadow: review findings against real evidence before any alerting (V272)

status: planned · target: 842ff7cb

2026-11-26: V273 (d2a95f34) adds the P2 layer behind p2_enabled, default off; it cannot be switched on while the P1 shadow period runs, so this review stays the gate for any alerting and for P2. No shadow findings were reviewed in this sync.
