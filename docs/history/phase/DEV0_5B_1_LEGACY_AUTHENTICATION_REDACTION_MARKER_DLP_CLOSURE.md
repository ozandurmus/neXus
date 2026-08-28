# DEV.0.5B.1 — Legacy Authentication Redaction Marker DLP Closure

## Objective
Remove the repository-owned legacy authentication redaction marker identified by enterprise DLP while preserving secret redaction behavior and native transport/security-detection vocabulary.

## Changes
- Replaced the legacy secret-redaction token with the canonical `[AUTH_SECRET:REDACTED]` token in runtime sensitive-value registrations.
- Updated credential-redaction regression expectations to the canonical authentication vocabulary.
- Added a source guard requiring zero occurrences of the legacy marker form in Python source.
- Preserved the existing zero-match guard for the known DLP assignment-collision form.

## Preserved
- Runtime secret values and authentication flow.
- Paramiko/PAN native protocol semantics.
- Secret/configuration detection vocabulary.
- Logger redaction mechanics.
- Repository privacy policy.

## Acceptance
- Python source legacy-marker matches: 0.
- Python source known assignment-collision matches: 0.
- Credential redaction test passes with `[AUTH_SECRET:REDACTED]`.
- Full regression and repository privacy gate pass.
- Corporate Copilot DLP smoke validation remains a real-environment acceptance step.
