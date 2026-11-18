# Panorama config-log change actor: commits logged Submitted while the join needs Succeeded

status: planned · target: PO_DECISION_RECORD_2026_11_12_PAN_CHANGE_ACTOR_FROM_CONFIG_LOG; trial switch on (642eb4ce)

2026-11-18: the Panorama config-log change-actor trial is on (642eb4ce, switch in the manifest). Known gap: commits are logged with result Submitted while the join to the change needs Succeeded, so those commits get no actor. Keep open until the join accepts the real log vocabulary and a measured sample confirms it.
