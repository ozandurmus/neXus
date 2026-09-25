# UI: one device screen, one count vocabulary, executive Overview (5 facts)

status: deferred · target: 

PO 2026-09-25: one device screen approved (Config nav removed); Overview as executive summary with five facts (recoverability, compliance, change, cluster inconsistency, version/patch lag); a fact a vendor lacks is not shown. 'Şu an için bu ekranlar ok' -- deferred, screens stay as they are for now. Council: docs/design/UI_EFFECTIVENESS_COUNCIL_2026_09_25_SYNTHESIS.md

Correction: 'Şu an için bu ekranlar ok' approved the five Overview facts (not a deferral). Building the executive Overview first.

Overview done (estate map, Fable spec). Next: merge Devices and Config into one device screen (approved).

2026-09-25: one device screen shipped -- Configuration is a tab of Devices (device and cluster, Check Point and Palo Alto only), the Config nav item is gone, old ?screen=configuration links land on Devices with the tab open; Devices list takes change_state=changed and cluster_diff=present; 'Read configuration, all' in the Devices toolbar.
