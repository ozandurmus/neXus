# Check Point configuration collection runs without a virtual system context, so for VSX the interfaces and routing sections hold the member's view rather than each virtual system's

status: planned · target: ConfigurationReadPlan CP read plan; CheckPointGaiaConfigParser section list

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Check Point configuration collection runs without a virtual system context, so for VSX the interfaces and routing sections hold the member's view rather than each virtual system's

Target (full): ConfigurationReadPlan CP read plan; CheckPointGaiaConfigParser section list

2026-11-14 backlog truth: still open (P3, size M). Today: VSX interfaces/routes in Configuration show the member view, not each virtual system. Evidence: ConfigurationReadPlan.java CG-1: one host-level Gaia read, no per-virtual-system repetition
