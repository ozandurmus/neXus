# Observed device facts follow every completed read: hostname, model, software version (and hotfix/platform facts) are refreshed by confirm, inventory and configuration runs whenever they differ -- the fill-if-absent rule is removed (PO 2026-09-25: an MDS upgrade was not reflected)

status: in_progress · target: ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/device/DeviceRepository.java

2026-09-25 02:25: real-environment validated. After deploy 5 a Collect on the upgraded MDS completed in 276 s and the device row moved from R81.20 (frozen since the 2026-09-22 confirm) to R82 with model Smart-1 5150; worker log OBSERVED_FACTS_REFRESHED. The hotfix level is empty on R82 (no jumbo installed yet -- cpinfo lists none), uptime reset confirms the upgrade. Decision record docs/design/PO_DECISION_RECORD_2026_09_25_OBSERVED_FACTS_FOLLOW_EVERY_READ.md.
