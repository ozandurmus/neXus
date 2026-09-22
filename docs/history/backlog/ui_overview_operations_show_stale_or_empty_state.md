# UNVERIFIED claim: Overview and Operations render empty state while devices and jobs exist

status: planned · target: verify against a running deployment before scoping

2026-09-22, Product Owner: reproduced again as aiview after the inventory unification deploys -- Overview shows '0 devices enrolled · nothing collected yet' and all four posture cards at 0 while Devices lists 103 enrolled devices with collected inventory. PO directs this stays P1 and is picked up right after the config > compliance > backup pass.

2026-09-22: root cause -- OverviewScreen and the Operations metrics/tabs were static mockups ('0 devices enrolled' was a string constant). Overview now counts from /devices, /configuration, /backups, /api/v2/jobs and the compliance overview (a failed read says 'read failed', never 0); Operations metrics come from the jobs API (24 h submitted, success rate, in flight, failed) and its Jobs/Queue/History tabs are the real Jobs panel with state filters.
