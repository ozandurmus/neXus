# 512 MiB spike data cap not enforced by the pruner

status: planned · target: docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md

2026-10-20 (open). Phase B contract section 11 gives cp_spike_data a 30-day retention and a 512 MiB budget (marked PENDING). The collector (2cd44137) enforces 16 MiB per directory, 16 directories per run and counts spike bytes against the device budget, but the retention pruner only ages rows out at 30 days; nothing deletes the oldest spike archives when a device passes 512 MiB. Spike collection is off by default (system_log.spike_data_enabled) so the gap is latent until a security_admin turns it on. Closure: pruner enforces the cap oldest-first with a ledgered count, then the contract row loses PENDING.
