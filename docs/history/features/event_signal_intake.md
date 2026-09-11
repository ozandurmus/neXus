# event_signal_intake — External Change Signal Intake & Bounded Evidence Trigger

## summary

Authenticated webhook ingress (e.g. Splunk correlation action) that validates, deduplicates and rate-limits inbound signals, resolves canonical device identity, and queues a bounded read-only evidence capture through the collection coordinator. Requires safe-diff and collection coordinator foundations.

## why

Enables near-real-time configuration snapshots after policy-install events without polling, while preserving all existing safety and identity contracts.
