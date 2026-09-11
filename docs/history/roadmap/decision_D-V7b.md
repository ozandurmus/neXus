# D-V7b — CP configured-recovery machine-readable read surface. STILL_UNKNOWN after four sessions -- the Simple Cluster API is officially documented as not exposing every cluster-object feature ("use SmartConsole"), and the official CheckPointSW Ansible simple-cluster module's full parameter list has no recovery/failback field. Does NOT block OP.0b.0 freeze -- check 6 (preemption_known) was originally specified "recorded, non-blocking" (session 1) in the design prose. Blocks CLASS 2 specifically (bug register CP-3, P0 before CLASS 2).

## Options

- Official GitHub mirror search (Check Point generic-object API schema, unconfirmed to exist)
- Human-fetched official page body

## Recommendation

Do not invent an attribute name. Not a freeze blocker; required before CLASS 2.
