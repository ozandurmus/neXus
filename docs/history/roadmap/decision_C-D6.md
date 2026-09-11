# C-D6 — Mandatory operator reason text on every operational-write, and its retention?

## Options

- Mandatory, bounded, redaction-filtered, excluded from the support bundle
- Optional
- Not collected

## Recommendation

Mandatory (8-200 chars), filtered through the redaction registry before persistence, never in the support bundle. It is the difference between an audit log and a list of timestamps.
