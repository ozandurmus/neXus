# DEV.0.5B.2 — Repository-Wide DLP Collision Closure

## Objective

Extend the authentication DLP closure from Python-only checks to the complete repository text candidate used by Corporate Copilot.

## Changes

- Removed repository documentation and metadata that repeated the two enterprise-DLP collision forms already eliminated from production Python.
- Removed the legacy redaction-token spelling from the repository privacy scanner safe-literal list and aligned it with the canonical authentication-secret token.
- Reworded the historical direct-SSH override documentation so it describes the compatibility capability without embedding the DLP-sensitive assignment form.
- Added a repository-wide regression guard covering source, tests, documentation, shell, templates, examples, metadata, and other recognized text files.
- The guard constructs its search expressions without embedding either forbidden form in repository text.

## Preserved

- Runtime authentication behavior.
- Paramiko and vendor-native protocol semantics.
- Configuration secret detection and repository privacy detection vocabulary.
- Existing runtime environment compatibility.
- Network commands, retries, timeouts, concurrency and device-selection behavior.

## Validation

- Known DLP collision form A across repository text: zero findings.
- Known DLP collision form B across repository text: zero findings.
- Repository privacy gate must remain PASS.
- Full automated regression must retain the two known xfails only.

## Corporate Copilot acceptance

Repeat the previously blocked scoped authentication-consumer audit without weakening its requested scope. A further DLP block must be diagnosed from the enterprise rule evidence before any additional repository change.
