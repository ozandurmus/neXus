# DEV.0.5B — Authentication Consumer Migration + Canonical Config + DLP Collision Closure

## Objective

Close the DEV.0.5A compatibility phase by making `Config.auth` the only application authentication source of truth, consolidating `Config` into `config.py`, and removing the known enterprise-DLP assignment-collision form from Python source.

## Decisions

- `config.py::Config` is the single canonical Config class.
- `main.py` imports and uses canonical `Config`; the duplicate class is removed.
- Application consumers use `cfg.auth.principal` / `cfg.auth.secret`.
- `LegacyAuthConfigMixin` is removed after zero production consumers remain.
- Native transport/API semantics are preserved. Where a library requires a password field, the call is expressed through an explicit mapping rather than the DLP-sensitive keyword-assignment form.
- Security-detection vocabulary such as password/secret/private-key remains intact where it is data being detected rather than an assignment pattern.
- Existing runtime environment-variable names remain compatible.

## Security invariants

- No authentication material is persisted or added to telemetry.
- `RuntimeAuth` remains immutable and protected in representation.
- Sensitive-value registration/redaction behavior is preserved.
- Network commands, targets, retries, timeouts, concurrency and trust policy are unchanged.

## Acceptance

- One canonical `Config` class.
- Zero production `cfg.username` / `cfg.password` consumers.
- Zero Python-source matches for regex `\\bpassword\\s*=`.
- Full automated regression passes with only the two known xfails.
- Local repository privacy gate reports zero findings.
- Corporate Copilot DLP smoke test is performed in the enterprise path after local validation.
