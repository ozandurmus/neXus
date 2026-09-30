# PO decision record 2026-09-30: HTTPS appliances -- pin the certificate on first contact, warn on change

**Status:** RATIFIED -- Product Owner, 2026-09-30 (chat, option "b").

## Finding (in-host Semgrep, 2026-09-30)
`HttpsDeviceClient` (Infoblox, Radware Cyber Controller / DefensePro via the Cyber Controller, Pulse/Ivanti and the
other HTTPS appliance backups) reuses the Palo Alto trust-all trust manager and disables endpoint identification.
`PO_DECISION_RECORD_2026_09_21_PAN_TLS_VERIFICATION_DISABLED.md` covers Palo Alto only, so these vendors had no
decision: a machine in the path could present any certificate and read the backup session's credentials.

## Decision
Trust on first use, the same model as SSH host keys:
1. On the first successful HTTPS contact, neXus records the device certificate's SHA-256 fingerprint (and subject /
   issuer / validity, masked on screen) against the device endpoint.
2. Later contacts compare the presented certificate with the pinned one. **Match** -> proceed. **Mismatch** -> proceed
   and raise a visible warning (device screen, job result, notification) under the standing "warn, not refuse" rule
   for key changes; a security_admin accepts the new certificate with one click (the observed fingerprint is filled
   in by neXus, as for SSH keys).
3. An optional strict mode (per device, off by default) refuses on mismatch.
4. Palo Alto keeps its own 2026-09-21 decision; it may move to this model later on the PO's word.
