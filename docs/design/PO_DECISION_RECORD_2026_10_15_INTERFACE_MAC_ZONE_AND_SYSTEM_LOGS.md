# PO decision record — 2026-10-15: interface MAC/zone, and the device system-log programme

**Status: RATIFIED — PO APPROVED (chat, 2026-10-15).** Recorded by the engineering session from the Product
Owner's explicit chat answers ("1- ok, 2 için ok, ... 4- A ok ... Karar kaydına ok, AI view konusu ok").

## 1. Interface link-layer addresses (approved, implemented with V148)

- Palo Alto: `hw/entry/mac` from the already-gated `show interface all` — a parse-scope extension.
- Check Point: one new read, `ip -o link show` (Expert, CLASS_0 read, once per device per run, physical context
  only, `vsenv 0` on a VSX host). Gate entries 21/22 of `CP_INVENTORY_COMMAND_GATE_ENTRIES.md`, migration V148.
- Values are stored per interface. aiview sees the vendor prefix (OUI) only: `aa:bb:cc:**:**:**`.

## 2. Palo Alto interface zone (approved, implemented with V148)

`ifnet/entry/zone` from the same `show interface all` response. An interface without a zone has none, never a
guessed one. aiview sees a stable `ZONE-…` pseudonym.

## 3. Device system logs (approved: phase A now; B–D approved in direction)

The Product Owner wants a daily, per-device log pool, visible in the UI, analysed for likely problems.

- **A. Contract and measurement (approved now).** Name the files and reads per vendor (Check Point e.g.
  `/var/log/messages`, `$FWDIR/log/*.elg`; Palo Alto system log), measure size and growth, and put every read
  through the network-device command gate. Each new command still needs the Product Owner's exact-command OK.
- **B. Incremental collection and storage (approved in direction).** Only new lines since the last read, daily,
  encrypted at rest, with an operator-set retention, viewable in a UI screen.
- **C. Prioritisation (approved in direction).** AI-assisted mapping of log patterns to priorities, to surface
  critical events; the mapping stays editable.
- **D. Knowledge matching and an analysis agent (approved in direction).** A pattern-to-vendor-knowledge table
  (Check Point SK articles have no public API; entries are curated). An optional agent may search public
  sources and, when an account is connected in settings, query an AI API with **anonymised** data only, and
  show its suggestions as advisory warnings.

**Raw-evidence law exception.** Persisting device log lines is an explicit exception to the raw-evidence law,
authorised by this record for the system-log programme only. Its privacy handling is part of phase A's contract:
masking for aiview (addresses, users, hostnames), no credentials or secrets ever stored, encryption at rest, a
bounded retention, and anonymisation before anything leaves the host.

## Not authorized by this record

No new device command beyond `ip -o link show` until phase A names each read and the Product Owner approves it.
No log data leaves HOST-A un-anonymised.
