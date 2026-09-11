# passive_ti_enrichment — Passive Threat-Intelligence Enrichment for Confirmed Changes

## summary

After a normalized, confirmed diff finding is produced, extract safe observables and check against a local/corporate-proxy TI feed. Result is stored as contextual risk enrichment, not root-cause evidence. Internet API query is off by default.

## why

Surfaces potential risk context without manual investigation, while keeping raw config, full topology and customer IP ranges inside the evidence boundary.
