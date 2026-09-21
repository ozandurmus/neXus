# PO Decision Record — 2026-09-21 — Palo Alto TLS certificate verification removed

## Status

**RATIFIED — PRODUCT OWNER DECISION, 2026-09-21.** Supersedes, for Palo Alto only,
the TLS posture stated in `AGENTS.md`'s Palo Alto section ("Production TLS requires
trusted corporate CA verification. Historical POC TLS-verification exceptions are
technical debt, never production design.") and reverses NXS-LOCAL-0359 and
NXS-LOCAL-0363's per-device enrollment model.

## Decision

The Product Owner directed, explicitly and after being told what it means, that
`PanXmlApiTransport` accept whatever certificate a Palo Alto device presents,
with no chain verification and no per-device operator enrollment step. Session
transcript, 2026-09-21: the environment is entirely internal ("içeride kurum
kaynaklarını consume ediyorum"), every one of the 39 enumerated firewalls
presents its own self-signed certificate, and the Product Owner assessed the
per-device enrollment flow (NXS-LOCAL-0363) as unwanted operational friction
for that environment, not a control worth keeping. Told plainly that this means
any certificate is accepted -- including one presented by a device that is not
actually the firewall it claims to be -- the Product Owner repeated the
instruction and directed it be implemented.

## What changes

- `PanXmlApiTransport`'s trust manager stops verifying the presented
  certificate's chain and stops consulting `management_endpoint_pan_cert_trust`
  or any pinned-fingerprint value. Any certificate is accepted.
- The `PAN_DISCOVERY_TRUST_CA_BUNDLE_PATH` and pinned-fingerprint environment
  variables become inert for Palo Alto; nothing reads them for a trust decision
  once this ships.
- The `management_endpoint_pan_cert_trust` table, its migration, and its
  audit trigger are not dropped -- existing rows are historical fact and the
  append-only grant makes them undeletable regardless. No new row will be
  written by ordinary operation once nothing enrolls.
- The "Certificate trust authorization" step in `AddDeviceDialog` for Palo
  Alto is removed; there is nothing left to enroll.

## What does not change

- Check Point's SSH host-key trust enrollment (C10) is untouched.
- The retention ledger, audit log, and every other append-only table keep
  their existing grants and existing rows -- nothing here authorizes deleting
  history, and nothing here could even if it tried.
- Discovery parsing, collection behaviour, and what is gathered once a
  connection succeeds are unaffected.

## Why this is recorded rather than silently patched

`AGENTS.md`'s Contract-status law requires a disagreement between two
authorities to be reported, not silently reconciled. This file is that report,
and its `RATIFIED` status is the Product Owner resolving it. `AGENTS.md`'s own
Palo Alto section should be read alongside this record for Palo Alto TLS
specifically: the general rule stands for any future vendor or environment
that does not carry this same decision.
