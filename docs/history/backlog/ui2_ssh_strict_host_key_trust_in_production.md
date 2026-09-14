# UI2 SSH host-key trust: on mismatch connect, warn with the MDS view alongside, audit -- strict refusal is a configurable production option, off by default (PO 2026-09-13, record 13F)

status: planned · target: ui2 worker HostKeyVerifier / TrustRuleResolver; successor to cp_production_ssh_host_key_trust_hardening + cp_ssh_trust_r2_prod_server

2026-09-13 PO directive (PO_DECISION_RECORD_2026_09_13F section 2): a host-key or serial mismatch does NOT refuse the connection; the product connects with the credentials, shows a visible warning next to what the management plane reports, and audits it. Strict refusal stays a configurable production option, off by default. The earlier 'mandatory strict enforcement' wording is withdrawn.

The connect-on-mismatch-warn-with posture switch exists in the worker's HostKeyVerifier/TrustRuleRepository; only the production enforcement default remains open.
