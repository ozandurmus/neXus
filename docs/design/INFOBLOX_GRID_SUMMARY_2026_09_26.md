# Infoblox Grid Manager summary

## Status

**FROZEN — Product Owner directive, 2026-09-26.** Local implementation and schema migration are approved. No live device command or deployment is approved by this contract.

## Scope

Inventory reads only `view(name)`, `zone_auth(fqdn)`, `network(network,utilization)`, `range(start_addr)`, and `member:license(type,kind,expiry_date,hwid)` on the versioned WAPI endpoint. Each is paged with `_paging=1&_max_results=1000&_return_as_object=1`, up to 100 pages. A failed object leaves its part unknown; a page cap marks its count as at least the measured number. Utilization is tenths of a percent. License `hwid` may be used only for an in-memory member join; it is never persisted or returned.

The summary belongs to the completed inventory run. The service returns it as `grid_summary`, null for other vendors. AIView masks network CIDRs and license member names through the existing subnet and topology masking paths. Missing parts have no UI heading. Existing member HA and service facts remain observations, not inferred health.

## Validation boundary

Fixture tests and local compilation establish implementation behavior. Real WAPI shape, license joinability, migration application, and AIView visual acceptance remain unverified until a separately authorized engineering session. No ad hoc device diagnostic is authorized here.
