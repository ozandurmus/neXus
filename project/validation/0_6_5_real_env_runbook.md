# 0.6.5 PAN TLS/CA Trust - Real-Environment Validation Runbook

Status: READY_FOR_HUMAN_REAL_ENV
Date: 2026-08-27
Build: 0.6.5

## Scope

- Validate production-admission behavior for PAN strict TLS/CA trust path.
- Keep existing collector behavior unchanged.
- Keep run read-only and value-free.

Out of scope:

- New commands, retries/timeouts/polling/concurrency changes.
- CAS/storage/UI/schema changes.
- Any network device write/change operation.

## Preconditions

- A corporate CA bundle file is provisioned on the validation host.
- Runtime and credentials are already configured in the approved environment.
- Validation runner has access to the existing repository checkout.

## One Preferred Validation Command

PowerShell:

```powershell
$env:SECURITYEXPERT_PAN_CA_BUNDLE="C:\\APPROVED\\PATH\\corporate-ca.pem"; py -B main.py --only pan-config --pan-config-stage 5 --pan-config-workers 1
```

Command intent:

- Forces strict TLS verification with an explicit CA bundle path.
- Uses a bounded PAN config scope and single worker for conservative device load.

## PASS Criteria

- No TLS trust fallback to verify=False when strict CA bundle mode is enabled.
- PAN config collection executes with strict verify input accepted.
- No value leakage in shareable evidence (no credentials, endpoint identity, or raw secret-bearing config).
- No drift in frozen scope: no scheduler/polling/concurrency semantics change.

## Suggested Safe Evidence to Return

- Command exit status.
- Value-free run summary (success/partial/failed counts).
- Presence/absence of strict preflight error class only (no sensitive raw logs).
- Optional redacted note confirming CA bundle was provisioned and readable.

## Report Finalization Command

After operator safe summary is available, finalize the repository report template:

```powershell
py -B _realenv_0_6_5_finalize_report.py --result PASS --success 5 --partial 0 --failed 0 --management-down 0 --attested
```

This updates [project/validation/0_6_5_real_env_report_template.json](project/validation/0_6_5_real_env_report_template.json) with
validation date, check status, pass-rate, deployment gate and sanitized summary counts.

## Failure Interpretation

- `pan_tls_ca_bundle_preflight_failed`: strict input was rejected before HTTPS collection; treat as expected gate behavior for invalid/unreadable bundle input.
- TLS handshake/cert validation error with strict mode on: trust chain provisioning issue; do not switch to insecure compatibility mode for production admission.

## Operator Safety Notes

- Do not paste full runtime logs into chat.
- Do not publish real file paths, hostnames, IPs, tokens, usernames, or raw config snippets.
- Share only value-free summaries and sanitized evidence.