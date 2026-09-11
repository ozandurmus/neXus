# op0b_0_vendor_failover_preflight_evidence_surface_contract_draft — OP.0b.0 - Vendor failover preflight evidence surface contract (DRAFT -- DO NOT FREEZE)

## Summary

Structurally complete evidence-surface contract for the ~16-command HA preflight battery (command table, configuration/runtime field trace, bug/gap register) built from repository source, recorded real-environment findings and vendor-doc search snippets. DRAFT -- DO NOT FREEZE: several safety-critical vendor command semantics (D-V1-D-V7, D-V9) remain UNKNOWN because official vendor documentation hosts returned CONNECT 403 from the drafting environment's egress proxy; fetching those documents from an unblocked network is the only work between this draft and FREEZE.

## Evidence

Document only; no code changed. §24 command surface table, §25 configuration/runtime field trace table and §26 bug/gap register fully populated; every semantic either cited to an official source or marked UNKNOWN.

## Risks forward

Must not be cited as approving any command, schema or identity model -- see AGENTS.md Authority hierarchy #2 and Contract-status law. D-V8, D-T1, D-F1, D-F2, D-P1 are open but non-blocking.
