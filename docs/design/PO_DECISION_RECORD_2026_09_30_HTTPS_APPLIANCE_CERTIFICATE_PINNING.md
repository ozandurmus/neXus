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

**Amendment 2026-10-18 (PO, chat):** certificate expiry does not change identity verification -- "Identity verified" stays while the pinned fingerprint matches, the device header shows a separate red "Certificate expired N d ago" pill, and collection is unchanged.

**Amendment 2026-10-18 (PO, chat) -- "Identity verified" is bound to the real match.** The device header badge used to mean
only "enrolled". It now follows the worker's last comparison of what the device presented with what is pinned for it
(SSH host key for SSH devices, TLS leaf-certificate fingerprint for HTTPS appliances):
- green **Identity verified**: the last comparison matched;
- red **Identity mismatch**: the last comparison did not match, or `devices.identity_mismatch_state` is `OPEN` (the identity
  the device presented differs from the one recorded at enrollment);
- grey **Not verified yet**: no comparison is on record -- never compared, the newest connection pinned the fingerprint
  itself (`FIRST_USE`: nothing to compare, so not a match), or the transport has no pin (Palo Alto XML API, verification off
  by `PO_DECISION_RECORD_2026_09_21_PAN_TLS_VERIFICATION_DISABLED.md`).

Where the result is recorded: before this amendment a match left no record (only `ssh_host_key_observation` rows for SSH
mismatches and `https_endpoint_cert_trust` PENDING rows for changed certificates existed, and neither says what the *last*
connection did). Migration V165 adds `device_identity_check` (one row per device: transport, verdict
`MATCH | MISMATCH | FIRST_USE`, time; no fingerprint, key or address), written by the worker after each pinned comparison --
`SshHostKeyPolicyStore` (SSH match and mismatch) and `HttpsCertificateTrustRepository.observe` (HTTPS). The write runs after,
and apart from, the trust decision and swallows its own failure, so **collection behaviour is unchanged**. The service
exposes it as `identity_check { state, basis, first_use, checked_at }` on `GET /devices` and `GET /devices/{id}`. The expired-
certificate pill of the same date is untouched. Devices enrolled before V165 read "Not verified yet" until their next
connection records a comparison; nothing is backfilled, because a match that was not observed is not claimed.
