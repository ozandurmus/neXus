# op_aa_vsls_scope — PAN Active/Active and Check Point VSLS (per-VS load sharing) failover: first-class in OP.2, or deferred?

## Options

- first-class in OP.2
- deferred (OP.0 still assesses them)

## Recommendation

deferred - OP.2 covers CP ClusterXL HA and PAN A/P (the common cases); A/A and VSLS are a later adapter. OP.0/OP.1 assess and plan for them regardless.
