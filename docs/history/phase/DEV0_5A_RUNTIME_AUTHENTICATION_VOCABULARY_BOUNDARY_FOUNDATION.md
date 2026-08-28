# DEV.0.5A — Runtime Authentication Vocabulary & Boundary Foundation

Status: IMPLEMENTED / automated validation PASS / corporate Copilot DLP validation pending.

## Objective

Centralize interactive authentication material behind an in-memory `RuntimeAuth` boundary while preserving existing collector behavior through a temporary read-only compatibility adapter.

## Contract

- `RuntimeAuth.principal` and `RuntimeAuth.secret` are the authoritative runtime authentication state.
- `RuntimeAuth` is frozen and its representation never exposes either value.
- `Config.auth` is the single authentication source of truth.
- Existing collectors remain unchanged in DEV.0.5A through `LegacyAuthConfigMixin`; consumer migration is deferred to DEV.0.5B.
- Interactive labels use `Login:` and `Authentication secret:`.
- Sensitive-value registration uses `AUTH_PRINCIPAL` / `AUTH_SECRET` redaction labels.
- Native transport/API vocabulary and security-detection vocabulary are intentionally unchanged.
- No network, timeout, retry, concurrency, device-selection, TLS, SSH trust, or evidence behavior changes are part of this build.

## Validation

- Focused authentication/runtime endpoint tests: PASS.
- Full regression: 222 passed / 2 known xfailed.
- Clean candidate repository privacy gate: 0 findings / PASS.
- Corporate Copilot DLP smoke test: pending real enterprise inspection-path validation.

## Stop condition

If Copilot still blocks the new core authentication boundary because of legitimate authentication-handling semantics or standard-library APIs, do not obfuscate or alias code to evade DLP. Treat that as an enterprise policy boundary.
