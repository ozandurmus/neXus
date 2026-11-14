# DEPLOY.1 â€” Evidence Egress & Sharing Policy

status: planned · target: DEPLOY.1

Explicit policy for every outbound path: UI export, support bundle, audit log, webhook ingress persistence, TI enrichment query, OIDC claim retention. Must be defined before event intake or TI egress paths are opened.

2026-11-14 backlog truth: still open (P2, size M). Today: No single written policy for exports, mail, advisory fetch; no live leak known. Evidence: No single egress policy doc; DEPLOY.1 Python-era (roadmap blocked); egress is per feature (advisory-fetch NetworkPolicy, masking)
