# deploy1_oidc_viewer — DEPLOY.1A â€” Authenticated Read-Only Viewer Boundary

## summary

Corporate OIDC login gate in front of all UI and API surfaces before the server is opened for internal use. IP allowlist remains as a defense-in-depth layer, not the primary control.

## why

Network topology, rule sets and configuration evidence are sensitive even after redaction. Any internal server deployment must verify who is viewing before exposing the evidence plane.
