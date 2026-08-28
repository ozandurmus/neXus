# Phase 0.3.1 - Shareable Support Bundle

This build keeps the Phase 0.3 run-isolation and VSX prompt-aware reader behavior, and adds a privacy-preserving support bundle.

## What changes

- Full `--only all` runs automatically create `output/support_bundle_<run_id>.zip`.
- `py main.py --only support` regenerates a bundle for the latest run without requesting credentials or reconnecting to devices.
- Real device names, IP addresses, networks, serials, VS/VSYS names, interface identifiers, zone names and VR names are not placed in the support bundle.
- Stable pseudonyms use HMAC-SHA256. The local HMAC key is stored at `data/.support_hmac.key` and is never included in the bundle.
- The same real identifier maps to the same pseudonym across runs as long as the local key is preserved, allowing cross-run comparisons without exposing the real value.
- Artifact SHA-256, byte size, JSON validity, object counts, entity fingerprints and anonymized verification findings are included.
- VSX collection adds a separate `vsx_telemetry.json` artifact containing command duration, bytes, lines, prompt detection and timeout flags. It does not contain command output.
- VSX raw-to-parsed diagnostics compare raw interface/route candidate counts with parsed counts per anonymized logical context.

## Support bundle contents

- `summary.json`
- `manifest_anonymized.json`
- `verification_anonymized.json`
- `anomalies.json`
- `integrity.json`
- `inventory_fingerprints.json`
- `diagnostics.json`
- `support.log`

## What to share

Share only `output/support_bundle_<run_id>.zip` for normal troubleshooting.

Do **not** share:

- `data/.support_hmac.key`
- the real `output/*.json` files unless specifically required after support-bundle analysis
- credentials or API keys

## Security note

Plain SHA-256 of IPv4 values is intentionally not used for pseudonymization because the IPv4 search space can be brute-forced. HMAC-SHA256 with a secret local key prevents this while retaining stable correlation across runs.
