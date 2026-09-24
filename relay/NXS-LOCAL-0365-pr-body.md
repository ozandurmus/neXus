## Summary

- accept any Palo Alto certificate that is structurally present and within its X.509 validity period
- remove Palo Alto CA-bundle, fingerprint, repository lookup, and enrollment UI paths
- preserve expired/malformed certificate rejection and leave Check Point SSH trust and audit history untouched

## Validation

- `ui2/gradlew -p ui2 :worker:test`
- `npm --prefix ui2/frontend test` (128 passed)
- `npx --prefix ui2/frontend tsc -p ui2/frontend/tsconfig.json --noEmit`
- `python3 main.py --repository-privacy-check` (0 findings)
- `git diff --check origin/main`

Additional full Python regression: 3,757 passed, 25 skipped; non-green only for unrelated existing repository snapshot/deployment gates and Chromium launch denied by the managed sandbox.
