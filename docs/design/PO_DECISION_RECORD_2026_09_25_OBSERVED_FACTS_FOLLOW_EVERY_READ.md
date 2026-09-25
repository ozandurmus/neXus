# PO decision record 2026-09-25 -- observed device facts follow every read

**Decision (Product Owner, 2026-09-25, night):** "Nothing is filled only when empty. Every read looks, and
whatever changed is updated." A device's observed facts -- hostname, model, software version, and the
platform facts (serial, hotfix level, platform family, uptime) -- are refreshed by every completed
confirm, inventory and configuration read whenever the read's value differs from the stored one.

**Why:** the Check Point MDS was upgraded on 2026-09-24/25 (uptime reset, the jumbo hotfix line gone
from `cpinfo -y all`). The configuration read at 02:01 and the inventory read at 02:06 both completed,
yet the device still showed `R81.20`: the device row's `observed_software_version` was written once at
the enrollment confirm and afterwards only "filled if absent" (`fillObservedIdentityIfAbsent`, CG-1,
2026-09-24), so no later read could correct it. Interface addresses, routes and the configuration
text were never affected -- each run writes its own record and the newest run is what the screens show.

**What changed (V72 build, 2026-09-25):**
- `DeviceRepository.refreshObservedFacts(hostname, model, softwareVersion)` replaces the fill-if-absent
  method: each present value replaces the stored one when it differs; absent values leave the stored
  one alone; the identity baseline (`recorded_identity_*`, the mismatch mechanism) is untouched.
- Configuration runs (Check Point: `show hostname` + `show version all`; Palo Alto: `show system info`)
  refresh hostname and version after every completed read.
- Inventory runs refresh hostname, model and version: Check Point from the presented identity, the
  asset family and a `clish -c 'show version all'` read on the same session (the configuration path's
  gated literal, `cp_configuration_show_version_all`); Palo Alto from `show system info` (`hostname`,
  `model`, `sw-version`).
- The platform facts (`device_platform_facts`) already upserted per run; unchanged.

**Boundaries kept:** identity mismatch handling is unchanged (a presented identity that differs from
the recorded baseline is still evaluated by `IdentityMismatchEvaluator`, and a strict posture still
refuses the contact). No new device command: the version literal was already gated.

**Verification:** worker 292, persistence 70, service inventory/configuration 65 tests green; the next
inventory or configuration read of the MDS must show its post-upgrade version on the Devices screen.
