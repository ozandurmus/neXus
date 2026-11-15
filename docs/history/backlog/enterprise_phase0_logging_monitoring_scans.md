# Enterprise phase 0: question pack, JSON logs, metrics/traces, secrets provider, SAST

status: planned · target: ENTERPRISE_STACK_ALIGNMENT 0.1, 0.3-0.6

2026-11-10 (plan refresh) (PO 2026-11-10, phase 0, no enterprise dependency): 0.1 send the architecture team the question pack (decision record section 4); 0.3 one structured JSON console log format for every role with correlation ids per request and per job, field set configurable for the ARK format; 0.4 health, metrics and trace propagation that an Instana agent or OpenTelemetry can pick up (Instana ingestion UNKNOWN until access exists); 0.5 platform secrets through one file-based provider so CyberArk plugs in, firewall credentials stay in neXus with a written exception; 0.6 add a SAST scanner to CI and clear its findings, remove the npm TLS bypass in ui2/Containerfile and self-host the icon font. Authentication (0.2) is spring_boot_4_spring_security_migration.

2026-11-14 backlog truth: still open (P1, size L). Today: Logging, metrics, secrets provider, SAST and npm TLS bypass fix all open. Evidence: ui2/Containerfile line 33 still npm strict-ssl false; no JSON log/metrics libs or SAST in build; decision record 0.1, 0.3-0.6

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Part built: 0.3 JSON console logs ('Add opt-in JSON console logs (NEXUS_LOG_FORMAT=json) for the service', 'Worker JSON logs ... with masking'; correlation ids not verified) and 'Mask IPv4/IPv6 literals in application log output'. 0.6 npm TLS bypass is fixed: ui2/Containerfile now sets strict-ssl true with the corporate CA (commit 'Trust system-extracted corporate CA bundle for npm', on main). Open: 0.1, 0.4, 0.5, SAST, icon font.
